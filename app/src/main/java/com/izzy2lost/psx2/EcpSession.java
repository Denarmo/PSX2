package com.izzy2lost.psx2;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;

import com.izzy2lost.psx2.ecp.EcpLink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One Emulator Control Protocol session (SPEC sections 3-6): connects to the frontend that
 * started {@link EcpPlayActivity}, boots the game it sends in launch, and maps its requests onto
 * PSX2's native functions. ECP.md in the repository root describes each mapping.
 */
final class EcpSession implements EcpLink.Listener, EcpSettings.SessionState {
    private static final String TAG = "EcpSession";

    static final String ACTION_PLAY = "io.github.ecp.action.PLAY";
    static final String EXTRA_PORT = "io.github.ecp.extra.PORT";
    static final String EXTRA_TOKEN = "io.github.ecp.extra.TOKEN";
    static final String EXTRA_SESSION = "io.github.ecp.extra.SESSION";
    static final String EXTRA_MENU_INTENT = "io.github.ecp.extra.MENU_INTENT";
    static final String EXTRA_PATH = "io.github.ecp.extra.PATH";

    private static final int PROTOCOL = 1;
    private static final int SLOT_MAX = 99;

    private final EcpPlayActivity mActivity;
    private final int mPort;
    private final String mToken;
    private final String mSessionId;
    private final PendingIntent mMenuIntent;
    private final Uri mData;
    private final String mPath;

    private final EcpLink mLink = new EcpLink();
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "EcpWorker"));
    private final ScheduledExecutorService mTimer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "EcpWatch");
        t.setDaemon(true);
        return t;
    });
    private final EcpSettings mSettings = new EcpSettings(this);
    private final Set<String> mCaps = new HashSet<>();
    private final Object mPauseEventLock = new Object();

    private volatile boolean mHelloDone, mLaunched, mFirstFrame, mStopping, mEnded, mStoppedSent;
    private volatile boolean mFrontendPause, mMenuOpen;
    private volatile long mIgnoreInputBefore;
    private Boolean mSentPaused;
    private long mLastPerf;

    private JSONObject mLaunchSettings = new JSONObject();
    private int[] mChord = new int[0];
    private final Set<Integer> mChordHeld = java.util.Collections.synchronizedSet(new HashSet<>());

    private boolean mSpeedLimit = true, mFastForward;
    private float mSlowFactor = 1f;
    private int mVolume = 100;
    private boolean mMuted;

    EcpSession(EcpPlayActivity activity, Intent intent) {
        mActivity = activity;
        mPort = intent.getIntExtra(EXTRA_PORT, 0);
        mToken = intent.getStringExtra(EXTRA_TOKEN);
        mSessionId = intent.getStringExtra(EXTRA_SESSION);
        mMenuIntent = intent.getParcelableExtra(EXTRA_MENU_INTENT);
        mData = intent.getData();
        mPath = intent.getStringExtra(EXTRA_PATH);
    }

    static boolean hasEcpExtras(Intent intent) {
        return intent != null && intent.getIntExtra(EXTRA_PORT, 0) > 0;
    }

    String token() {
        return mToken;
    }

    /** Connects and says hello off the UI thread. */
    void start() {
        Log.i(TAG, "Session " + mSessionId + ": connecting to port " + mPort);
        mWorker.execute(() -> {
            try {
                mLink.connect(mPort, mToken == null ? "" : mToken, 10_000);
                mLink.start(this);
                JSONObject hello = descriptor();
                hello.put("type", "hello");
                hello.put("protocol", PROTOCOL);
                mLink.send(hello);
            } catch (Exception e) {
                Log.e(TAG, "Unable to reach the frontend", e);
                endSession(false);
                return;
            }
            // A frontend that never answers hello, or never launches, gets nothing running.
            mTimer.schedule(() -> {
                if (!mHelloDone) {
                    Log.w(TAG, "No hello from the frontend within 10 s");
                    endSession(false);
                }
            }, 10, TimeUnit.SECONDS);
            mTimer.schedule(() -> {
                if (!mLaunched && !mStopping) {
                    Log.w(TAG, "No launch from the frontend within 60 s");
                    endSession(false);
                }
            }, 60, TimeUnit.SECONDS);
        });
    }

    private JSONObject descriptor() throws IOException, JSONException {
        try (InputStream in = mActivity.getResources().openRawResource(R.raw.ecp)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject d = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            d.put("id", mActivity.getPackageName());
            try {
                d.put("version", mActivity.getPackageManager()
                        .getPackageInfo(mActivity.getPackageName(), 0).versionName);
            } catch (Exception ignored) {
            }
            return d;
        }
    }

    // ---------------------------------------------------------------------------------------
    // What the activity asks of the session

    boolean isMenuAttached() {
        return mCaps.contains("menu") && !mStopping;
    }

    boolean isPauseHeld() {
        return mFrontendPause || mMenuOpen;
    }

    void applyLaunchSettings() {
        Iterator<String> keys = mLaunchSettings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            EcpSettings.Setting s = mSettings.get(key);
            Object value = mLaunchSettings.opt(key);
            if (s == null || value == null || EcpSettings.validate(s, value) != null) {
                Log.w(TAG, "Ignoring launch setting " + key + "=" + value);
                continue;
            }
            try {
                s.setter.set(value);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to apply launch setting " + key, e);
            }
        }
        applyVolume();
    }

    /** Called on the emulation thread when the VM thread returns. */
    void onVmThreadEnded(boolean started, String startupError) {
        if (!mLaunched) return;
        mEnded = true;
        if (mStopping) return;
        if (!started) {
            String code = "content_invalid";
            String text = TextUtils.isEmpty(startupError) ? "PSX2 could not start this game." : startupError;
            if (text.toLowerCase(Locale.ROOT).contains("bios")) code = "bios_missing";
            sendStopped("error", error(code, text, startupError));
        } else {
            sendStopped("user", null);
        }
        endSession(false);
    }

    void onActivityDestroyed() {
        if (!mStopping && mLaunched) sendStopped("user", null);
        mStopping = true;
        mLink.close();
        mTimer.shutdownNow();
        mWorker.shutdown();
    }

    /** The player asked for the menu: the chord, the pause or menu button, or Back. */
    void requestMenu(String source) {
        if (!isMenuAttached() || !mFirstFrame || mMenuOpen) return;
        Log.i(TAG, "Menu requested by " + source);
        mMenuOpen = true;
        ignoreHeldInput();
        mActivity.refreshPauseState("ecp menu");
        mWorker.execute(() -> {
            waitPaused(true, 1500);
            syncPauseEvent();
            try {
                JSONObject event = new JSONObject().put("type", "menu_request");
                JSONObject frame = capture(1280, 960, 1280);
                if (frame != null) event.put("frame", frame);
                send(event);
            } catch (JSONException e) {
                Log.w(TAG, "menu_request", e);
            }
            sendMenuIntent();
        });
    }

    /** Menu chord detection, and held buttons ignored after the menu or a resume (SPEC 6.1). */
    boolean onInput(InputEvent event) {
        if (!(event instanceof KeyEvent)) return false;
        KeyEvent key = (KeyEvent) event;
        // Buttons pressed before the menu opened or the game resumed stay with the frontend.
        if (key.getDownTime() < mIgnoreInputBefore) return true;
        int code = key.getKeyCode();
        if (mChord.length == 0 || !contains(mChord, code)) return false;
        if (key.getAction() == KeyEvent.ACTION_DOWN) {
            mChordHeld.add(code);
            if (mChordHeld.size() == mChord.length && isMenuAttached() && mFirstFrame) {
                mChordHeld.clear();
                requestMenu("chord");
                return true;
            }
        } else if (key.getAction() == KeyEvent.ACTION_UP) {
            mChordHeld.remove(code);
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // Channel

    @Override
    public void onMessage(JSONObject msg) {
        String type = msg.optString("type");
        if ("hello".equals(type)) {
            onFrontendHello(msg);
            return;
        }
        if (!msg.has("id")) return; // events from the frontend: none are defined yet
        final long id = msg.optLong("id");
        if ("stop".equals(type)) {
            // Not queued behind a slow request: stop has a 5 s budget.
            new Thread(() -> handleStop(id), "EcpStop").start();
            return;
        }
        mWorker.execute(() -> {
            try {
                handleRequest(type, id, msg);
            } catch (Throwable t) {
                Log.e(TAG, type + " failed", t);
                replyError(id, "internal", "PSX2 could not do that.", String.valueOf(t));
            }
        });
    }

    @Override
    public void onClosed(Throwable error) {
        if (error != null) Log.w(TAG, "Channel closed", error);
        else Log.i(TAG, "Channel closed by the frontend");
        if (mStopping) return;
        // SPEC 4: without a frontend, save and exit within 5 s.
        new Thread(() -> {
            shutdownVm();
            endSession(false);
        }, "EcpClosed").start();
    }

    private void onFrontendHello(JSONObject msg) {
        if (msg.optInt("protocol", PROTOCOL) != PROTOCOL) {
            Log.e(TAG, "Frontend speaks protocol " + msg.optInt("protocol"));
            endSession(false);
            return;
        }
        int level = msg.optInt("level", 1);
        JSONArray caps = msg.optJSONArray("capabilities");
        Set<String> ours = new HashSet<>();
        try {
            JSONArray list = descriptor().getJSONArray("capabilities");
            for (int i = 0; i < list.length(); i++) ours.add(list.getString(i));
        } catch (Exception e) {
            Log.e(TAG, "descriptor", e);
        }
        if (level >= 2 && caps != null) {
            for (int i = 0; i < caps.length(); i++) {
                String c = caps.optString(i);
                if (ours.contains(c)) mCaps.add(c);
            }
        }
        mHelloDone = true;
        Log.i(TAG, "Frontend " + msg.optString("frontend") + " " + msg.optString("version")
                + ", level " + level + ", capabilities " + mCaps);
        mActivity.runOnUiThread(mActivity::onSessionNegotiated);
    }

    private void handleRequest(String type, long id, JSONObject msg) throws JSONException {
        if (!mHelloDone && !"launch".equals(type)) {
            replyError(id, "protocol", null, "request before hello");
            return;
        }
        String needs = capabilityFor(type);
        // resume also ends the menu hand-over, so menu alone allows it (SPEC 6.2 step 5).
        boolean allowed = needs == null || mCaps.contains(needs)
                || "resume".equals(type) && mCaps.contains("menu");
        if (!allowed) {
            replyError(id, "unsupported", null, type + " needs " + needs);
            return;
        }
        switch (type) {
            case "launch": handleLaunch(id, msg); break;
            case "pause": handlePause(id); break;
            case "resume": handleResume(id); break;
            case "save_state": handleSaveState(id, msg); break;
            case "load_state": handleLoadState(id, msg); break;
            case "list_states": handleListStates(id); break;
            case "delete_state": handleDeleteState(id, msg); break;
            case "reset": handleReset(id); break;
            case "set_speed": handleSetSpeed(id, msg); break;
            case "screenshot": handleScreenshot(id); break;
            case "set_volume": handleSetVolume(id, msg); break;
            case "hud_level": handleHudLevel(id, msg); break;
            case "notify": handleNotify(id, msg); break;
            case "get_schema": reply(id, new JSONObject().put("settings", mSettings.schema())); break;
            case "get_settings": reply(id, new JSONObject().put("values", mSettings.values())); break;
            case "set_setting": handleSetSetting(id, msg); break;
            default: replyError(id, "unsupported", null, "unknown request " + type);
        }
    }

    private static String capabilityFor(String type) {
        switch (type) {
            case "pause": case "resume": return "pause";
            case "save_state": case "load_state": case "list_states": case "delete_state": return "states";
            case "reset": return "reset";
            case "screenshot": return "screenshot";
            case "set_volume": return "volume";
            case "hud_level": return "hud";
            case "notify": return "notify";
            case "get_schema": case "get_settings": case "set_setting": return "settings";
            default: return null; // set_speed checks fast_forward / slow_motion itself
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle (SPEC 5)

    private void handleLaunch(long id, JSONObject msg) throws JSONException {
        if (mLaunched) {
            replyError(id, "busy", "A game is already running.", "second launch");
            return;
        }
        if (NativeApp.isVMActive() && !mActivity.isThread()) {
            replyError(id, "busy", "PSX2 ECP is already running a game. Close it and try again.",
                    "VM owned by another activity");
            return;
        }

        String game = resolveContent(msg.optString("content"));
        if (game == null) {
            replyError(id, "content_missing", "The game file could not be opened.",
                    "content " + msg.optString("content") + ", data " + mData + ", path " + mPath);
            return;
        }
        if (game.toLowerCase(Locale.ROOT).endsWith(".acgame")) {
            replyError(id, "content_invalid", "Arcade sets can only be started from PSX2's own library.",
                    ".acgame needs a folder grant");
            return;
        }

        BiosVerifier.ScanResult scan = BiosVerifier.scanVerifiedBioses(mActivity);
        boolean hasBios = scan.byRegion.containsKey(BiosVerifier.Region.USA)
                || scan.byRegion.containsKey(BiosVerifier.Region.EUROPE)
                || scan.byRegion.containsKey(BiosVerifier.Region.JAPAN);
        if (!hasBios) {
            replyError(id, "bios_missing",
                    "PSX2 ECP needs a PS2 BIOS dumped from your own console. Open PSX2 ECP from your "
                            + "app list, open its menu (top left) and choose Import / Manage BIOS.",
                    "no verified retail BIOS in " + BiosVerifier.getBiosDirectory(mActivity));
            return;
        }

        JSONObject settings = msg.optJSONObject("settings");
        if (settings != null) mLaunchSettings = settings;
        JSONArray chord = msg.optJSONArray("menu_chord");
        if (chord != null) mChord = chordKeys(chord);

        // A previous session's game in this activity: stop it before the new one boots.
        if (mActivity.isThread()) {
            NativeApp.shutdown();
            long until = SystemClock.uptimeMillis() + 5000;
            while (mActivity.isThread() && SystemClock.uptimeMillis() < until) sleep(50);
            if (mActivity.isThread()) {
                replyError(id, "busy", "The previous game is still closing.", "VM did not stop");
                return;
            }
        }

        mLaunched = true;
        reply(id, new JSONObject());
        Log.i(TAG, "Launching " + msg.optString("title", game) + " (" + msg.optString("game_id") + ")");
        JSONObject loading = new JSONObject().put("type", "loading");
        String title = msg.optString("title");
        if (!title.isEmpty()) loading.put("text", title);
        send(loading);
        mActivity.runOnUiThread(() -> mActivity.startSessionGame(game));
        mTimer.scheduleWithFixedDelay(this::watch, 100, 50, TimeUnit.MILLISECONDS);
    }

    private String resolveContent(String content) {
        String candidate;
        if ("intent".equals(content) || TextUtils.isEmpty(content)) {
            candidate = mData != null ? mData.toString() : mPath;
        } else {
            candidate = content;
        }
        if (TextUtils.isEmpty(candidate)) return null;
        if (candidate.startsWith("file://")) candidate = Uri.parse(candidate).getPath();
        if (candidate == null) return null;
        if (candidate.startsWith("content://")) {
            try (ParcelFileDescriptor pfd = mActivity.getContentResolver()
                    .openFileDescriptor(Uri.parse(candidate), "r")) {
                return pfd != null ? candidate : null;
            } catch (Exception e) {
                Log.w(TAG, "Cannot open " + candidate, e);
                return null;
            }
        }
        return new File(candidate).canRead() ? candidate : null;
    }

    private void handleStop(long id) {
        mStopping = true;
        shutdownVm();
        reply(id, new JSONObject());
        sendStopped("frontend", null);
        endSession(true);
    }

    /** Stops the VM, which writes the memory cards, and waits for its thread. */
    private void shutdownVm() {
        mStopping = true;
        if (!mActivity.isThread()) return;
        NativeApp.shutdown();
        long until = SystemClock.uptimeMillis() + 4000;
        while (mActivity.isThread() && SystemClock.uptimeMillis() < until) sleep(50);
        if (mActivity.isThread()) Log.w(TAG, "The VM is still shutting down");
    }

    private void endSession(boolean alreadyStopped) {
        mStopping = true;
        if (!alreadyStopped && mLaunched && !mStoppedSent && !mEnded) sendStopped("frontend", null);
        mLink.close();
        mActivity.runOnUiThread(mActivity::finishSession);
    }

    private void sendStopped(String reason, JSONObject error) {
        synchronized (this) {
            if (mStoppedSent) return;
            mStoppedSent = true;
        }
        try {
            JSONObject event = new JSONObject().put("type", "stopped").put("reason", reason);
            if (error != null) event.put("error", error);
            send(event);
        } catch (JSONException ignored) {
        }
    }

    /** Runs every 50 ms once the game is launched: first frame, pause events, perf. */
    private void watch() {
        if (mStopping) return;
        try {
            if (!mFirstFrame) {
                float[] perf = NativeApp.ecpGetPerf();
                if (perf != null && perf.length > 7 && perf[7] > 0 && NativeApp.isVMActive()) {
                    mFirstFrame = true;
                    send(new JSONObject().put("type", "first_frame"));
                    send(new JSONObject().put("type", "running"));
                    // runVMThread applies PSX2's per-game overrides after our launch settings;
                    // put back anything they changed.
                    mWorker.execute(this::reapplyLaunchSettings);
                }
                return;
            }
            syncPauseEvent();
            long now = SystemClock.uptimeMillis();
            if (mCaps.contains("perf") && now - mLastPerf >= 1000 && !NativeApp.isPaused()) {
                mLastPerf = now;
                float[] p = NativeApp.ecpGetPerf();
                if (p != null && p.length >= 7) {
                    send(new JSONObject().put("type", "perf")
                            .put("fps", finite(p[0])).put("target_fps", finite(p[1]))
                            .put("speed", finite(p[2])).put("frame_ms", finite(p[3]))
                            .put("cpu_ms", finite(p[4])).put("gpu_ms", finite(p[5]))
                            .put("internal_fps", finite(p[6])));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "watch", t);
        }
    }

    private void reapplyLaunchSettings() {
        Iterator<String> keys = mLaunchSettings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            EcpSettings.Setting s = mSettings.get(key);
            Object value = mLaunchSettings.opt(key);
            if (s == null || value == null || EcpSettings.validate(s, value) != null) continue;
            if (!EcpSettings.holds(s, value)) {
                Log.i(TAG, "Re-applying " + key + " over PSX2's per-game setting");
                s.setter.set(value);
            }
        }
        applySpeed();
    }

    // ---------------------------------------------------------------------------------------
    // Pause and menu (SPEC 6.1, 6.2)

    private void handlePause(long id) {
        mFrontendPause = true;
        mActivity.refreshPauseState("ecp pause");
        if (mFirstFrame) waitPaused(true, 1000);
        reply(id, new JSONObject());
        syncPauseEvent();
    }

    private void handleResume(long id) {
        mFrontendPause = false;
        mMenuOpen = false;
        ignoreHeldInput();
        mActivity.runOnUiThread(mActivity::bringToFront);
        mActivity.refreshPauseState("ecp resume");
        if (mFirstFrame) waitPaused(false, 2000);
        reply(id, new JSONObject());
        syncPauseEvent();
    }

    private void ignoreHeldInput() {
        mIgnoreInputBefore = SystemClock.uptimeMillis();
        mChordHeld.clear();
        try {
            NativeApp.resetKeyStatus();
        } catch (Throwable ignored) {
        }
    }

    /** Sends paused or resumed when the VM's state differs from what the frontend was told. */
    private void syncPauseEvent() {
        if (!mFirstFrame || mStopping) return;
        synchronized (mPauseEventLock) {
            boolean paused = NativeApp.isPaused();
            boolean told = mSentPaused != null && mSentPaused;
            if (paused == told) return;
            mSentPaused = paused;
            try {
                if (paused) {
                    String reason = mMenuOpen ? "menu" : mFrontendPause ? "frontend" : "focus";
                    send(new JSONObject().put("type", "paused").put("reason", reason));
                } else {
                    send(new JSONObject().put("type", "resumed"));
                }
            } catch (JSONException ignored) {
            }
        }
    }

    private void waitPaused(boolean paused, long timeoutMs) {
        long until = SystemClock.uptimeMillis() + timeoutMs;
        while (NativeApp.isPaused() != paused && SystemClock.uptimeMillis() < until) sleep(10);
    }

    private void sendMenuIntent() {
        if (mMenuIntent == null) return;
        try {
            Bundle options = null;
            if (Build.VERSION.SDK_INT >= 34) {
                // Since Android 14 the sender must opt in to lending its foreground status.
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                        ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                        : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                options = o.toBundle();
            }
            mMenuIntent.send(mActivity, 0, null, null, null, null, options);
        } catch (Exception e) {
            Log.w(TAG, "MENU_INTENT failed", e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // States (SPEC 6.3). ECP slot 0 is PCSX2's resume slot (-1); slots 1-99 are its numbered slots,
    // so states saved here also appear in PSX2's own Save States list (slots 1-10).

    private static int coreSlot(int slot) {
        return slot == 0 ? -1 : slot;
    }

    private Integer slotOf(long id, JSONObject msg) {
        int slot = msg.optInt("slot", -1);
        if (slot < 0 || slot > SLOT_MAX) {
            replyError(id, "protocol", null, "slot must be 0-99");
            return null;
        }
        if (!mFirstFrame || !NativeApp.isVMActive()) {
            replyError(id, "busy", "The game is still starting.", "no VM yet");
            return null;
        }
        return slot;
    }

    private void handleSaveState(long id, JSONObject msg) throws JSONException {
        Integer slot = slotOf(id, msg);
        if (slot == null) return;
        long started = System.currentTimeMillis();
        boolean ok = NativeApp.saveStateToSlot(coreSlot(slot));
        File file = stateFile(slot);
        ok = ok && file != null && file.isFile() && file.lastModified() >= started - 2000;
        mActivity.refreshPauseState("ecp state saved");
        if (!ok) {
            replyError(id, "busy", "The state could not be saved. Try again in a moment.",
                    "saveStateToSlot failed or wrote nothing (memory card busy?)");
            return;
        }
        reply(id, stateEntry(slot, file));
    }

    private void handleLoadState(long id, JSONObject msg) {
        Integer slot = slotOf(id, msg);
        if (slot == null) return;
        File file = stateFile(slot);
        if (file == null || !file.isFile()) {
            replyError(id, "slot_empty", "That slot is empty.", null);
            return;
        }
        boolean ok = NativeApp.loadStateFromSlot(coreSlot(slot));
        mActivity.refreshPauseState("ecp state loaded");
        if (!ok) {
            replyError(id, "state_incompatible", "This state cannot be loaded.",
                    "loadStateFromSlot failed (other version, hardcore mode or memory card busy)");
            return;
        }
        reply(id, new JSONObject());
    }

    private void handleListStates(long id) throws JSONException {
        JSONArray states = new JSONArray();
        if (NativeApp.isVMActive() && mFirstFrame) {
            for (int slot = 0; slot <= SLOT_MAX; slot++) {
                File file = stateFile(slot);
                if (file != null && file.isFile()) states.put(stateEntry(slot, file));
            }
        }
        reply(id, new JSONObject().put("states", states));
    }

    private void handleDeleteState(long id, JSONObject msg) {
        Integer slot = slotOf(id, msg);
        if (slot == null) return;
        if (!NativeApp.ecpDeleteStateSlot(coreSlot(slot))) {
            replyError(id, "slot_empty", "That slot is empty.", null);
            return;
        }
        reply(id, new JSONObject());
    }

    private File stateFile(int slot) {
        String path = NativeApp.getGamePathSlot(coreSlot(slot));
        return TextUtils.isEmpty(path) ? null : new File(path);
    }

    private JSONObject stateEntry(int slot, File file) throws JSONException {
        JSONObject entry = new JSONObject().put("slot", slot).put("time", file.lastModified() / 1000);
        if (mCaps.contains("state_thumbnails")) {
            byte[] png = NativeApp.getImageSlot(coreSlot(slot));
            if (png != null && png.length > 0) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
                if (bounds.outWidth > 0) {
                    entry.put("thumbnail", new JSONObject()
                            .put("png", Base64.encodeToString(png, Base64.NO_WRAP))
                            .put("width", bounds.outWidth).put("height", bounds.outHeight));
                }
            }
        }
        return entry;
    }

    // ---------------------------------------------------------------------------------------
    // Other controls (SPEC 6.5)

    private void handleReset(long id) {
        if (!NativeApp.isVMActive()) {
            replyError(id, "busy", "The game is still starting.", "no VM yet");
            return;
        }
        NativeApp.ecpReset();
        reply(id, new JSONObject());
    }

    private void handleSetSpeed(long id, JSONObject msg) {
        boolean ff = msg.optBoolean("fast_forward", false);
        double factor = msg.optDouble("factor", Double.NaN);
        boolean slow = !Double.isNaN(factor) && factor < 1;
        if ((ff || (!slow && !Double.isNaN(factor))) && !mCaps.contains("fast_forward")
                || slow && !mCaps.contains("slow_motion")) {
            replyError(id, "unsupported", null, "set_speed needs fast_forward or slow_motion");
            return;
        }
        if (slow) {
            mSlowFactor = (float) Math.max(0.1, factor);
            mFastForward = false;
            NativeApp.ecpSetIniValue("Framerate", "SlomoScalar", Float.toString(mSlowFactor));
        } else {
            mSlowFactor = 1f;
            mFastForward = ff;
            if (ff && !Double.isNaN(factor) && factor > 1) {
                NativeApp.ecpSetIniValue("Framerate", "TurboScalar",
                        Float.toString((float) Math.min(10, factor)));
            }
        }
        applySpeed();
        reply(id, new JSONObject());
    }

    private void applySpeed() {
        int mode = mFastForward ? 1 : mSlowFactor < 1f ? 2 : mSpeedLimit ? 0 : 3;
        NativeApp.ecpSetLimiterMode(mode);
    }

    private void handleScreenshot(long id) throws JSONException {
        JSONObject image = capture(0, 0, 2560);
        if (image == null) {
            replyError(id, "busy", "No picture is available yet.", "ecpCaptureFrame returned nothing");
            return;
        }
        reply(id, new JSONObject().put("image", image));
    }

    private void handleSetVolume(long id, JSONObject msg) {
        if (msg.has("percent")) mVolume = Math.max(0, Math.min(100, msg.optInt("percent", mVolume)));
        if (msg.has("mute")) mMuted = msg.optBoolean("mute", false);
        applyVolume();
        reply(id, new JSONObject());
    }

    private void applyVolume() {
        NativeApp.ecpSetVolume(mVolume, mMuted);
    }

    private void handleHudLevel(long id, JSONObject msg) {
        NativeApp.ecpSetHudLevel(Math.max(0, Math.min(3, msg.optInt("level", 0))));
        reply(id, new JSONObject());
    }

    private void handleNotify(long id, JSONObject msg) {
        String text = msg.optString("text");
        if (!text.isEmpty()) NativeApp.ecpNotify(text, (float) msg.optDouble("seconds", 3));
        reply(id, new JSONObject());
    }

    private void handleSetSetting(long id, JSONObject msg) throws JSONException {
        String key = msg.optString("key");
        EcpSettings.Setting s = mSettings.get(key);
        if (s == null) {
            replyError(id, "unsupported", null, "unknown setting " + key);
            return;
        }
        Object value = msg.opt("value");
        String problem = value == null ? "no value" : EcpSettings.validate(s, value);
        if (problem != null) {
            replyError(id, "protocol", null, key + " " + problem);
            return;
        }
        s.setter.set(value);
        // Kept for the session only, so a restart of the VM in this session applies it again.
        mLaunchSettings.put(key, value);
        reply(id, new JSONObject().put("applied", s.applies));
    }

    // EcpSettings.SessionState
    @Override public boolean speedLimit() { return mSpeedLimit; }
    @Override public void setSpeedLimit(boolean on) { mSpeedLimit = on; applySpeed(); }
    @Override public int volume() { return mVolume; }
    @Override public void setVolume(int percent) { mVolume = percent; applyVolume(); }

    // ---------------------------------------------------------------------------------------
    // Helpers

    /** The current frame as a picture, fitted into maxW x maxH (0 = internal resolution). */
    private JSONObject capture(int maxW, int maxH, int limitWidth) {
        int[] data;
        try {
            data = NativeApp.ecpCaptureFrame(maxW, maxH);
        } catch (Throwable t) {
            Log.w(TAG, "capture", t);
            return null;
        }
        if (data == null || data.length < 3) return null;
        int w = data[0], h = data[1];
        if (w <= 0 || h <= 0 || data.length < 2 + w * h) return null;
        Bitmap bitmap = Bitmap.createBitmap(data, 2, w, w, h, Bitmap.Config.ARGB_8888);
        if (w > limitWidth) {
            int nh = Math.max(1, Math.round(h * (limitWidth / (float) w)));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, limitWidth, nh, true);
            bitmap.recycle();
            bitmap = scaled;
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png);
        try {
            return new JSONObject()
                    .put("png", Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP))
                    .put("width", bitmap.getWidth()).put("height", bitmap.getHeight());
        } catch (JSONException e) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private static int[] chordKeys(JSONArray chord) {
        int[] keys = new int[chord.length()];
        int n = 0;
        for (int i = 0; i < chord.length(); i++) {
            int code = keyCodeFor(chord.optString(i));
            if (code != KeyEvent.KEYCODE_UNKNOWN && !contains(Arrays.copyOf(keys, n), code)) keys[n++] = code;
        }
        return Arrays.copyOf(keys, n);
    }

    /** RetroPad names (SNES layout) to Android gamepad keys (Xbox layout). */
    static int keyCodeFor(String button) {
        switch (button) {
            case "b": return KeyEvent.KEYCODE_BUTTON_A;   // bottom
            case "a": return KeyEvent.KEYCODE_BUTTON_B;   // right
            case "y": return KeyEvent.KEYCODE_BUTTON_X;   // left
            case "x": return KeyEvent.KEYCODE_BUTTON_Y;   // top
            case "l": return KeyEvent.KEYCODE_BUTTON_L1;
            case "r": return KeyEvent.KEYCODE_BUTTON_R1;
            case "l2": return KeyEvent.KEYCODE_BUTTON_L2;
            case "r2": return KeyEvent.KEYCODE_BUTTON_R2;
            case "l3": return KeyEvent.KEYCODE_BUTTON_THUMBL;
            case "r3": return KeyEvent.KEYCODE_BUTTON_THUMBR;
            case "select": return KeyEvent.KEYCODE_BUTTON_SELECT;
            case "start": return KeyEvent.KEYCODE_BUTTON_START;
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    private static boolean contains(int[] list, int value) {
        for (int v : list) if (v == value) return true;
        return false;
    }

    private static double finite(float v) {
        return Float.isFinite(v) ? Math.round(v * 100.0) / 100.0 : 0.0;
    }

    private static JSONObject error(String code, String message, String detail) {
        JSONObject e = new JSONObject();
        try {
            e.put("code", code);
            if (message != null) e.put("message", message);
            if (!TextUtils.isEmpty(detail)) e.put("detail", detail);
        } catch (JSONException ignored) {
        }
        return e;
    }

    private void reply(long id, JSONObject fields) {
        try {
            fields.put("type", "result").put("id", id).put("ok", true);
            send(fields);
        } catch (JSONException ignored) {
        }
    }

    private void replyError(long id, String code, String message, String detail) {
        try {
            send(new JSONObject().put("type", "result").put("id", id).put("ok", false)
                    .put("error", error(code, message, detail)));
        } catch (JSONException ignored) {
        }
    }

    private void send(JSONObject message) {
        try {
            mLink.send(message);
        } catch (IOException e) {
            Log.w(TAG, "send " + message.optString("type") + ": " + e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
