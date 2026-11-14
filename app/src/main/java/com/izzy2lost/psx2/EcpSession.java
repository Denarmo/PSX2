package com.izzy2lost.psx2;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;

import io.github.ecp.Ecp;
import io.github.ecp.EcpEngine;

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
 * PSX2's native functions. The channel, hello and request routing come from the ECP Android SDK
 * (ecp-sdk/). ECP.md in the repository root describes each mapping.
 */
final class EcpSession implements EcpEngine.Callbacks, EcpSettings.SessionState {
    private static final String TAG = "EcpSession";
    private static final int SLOT_MAX = 99;

    private final EcpPlayActivity mActivity;
    private final EcpEngine.Session mIntentSession;
    private final Uri mData;
    private final String mPath;

    private volatile EcpEngine mEngine;
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
    // PSX2 pauses the VM to save or load a state; that is not a pause to report.
    private volatile boolean mStateBusy;
    private volatile long mIgnoreInputBefore;
    private long mLastPerf;
    private float mDrawnAtLaunch;

    private JSONObject mLaunchSettings = new JSONObject();
    private int[] mChord = new int[0];
    private final Set<Integer> mChordHeld = java.util.Collections.synchronizedSet(new HashSet<>());

    private boolean mSpeedLimit = true, mFastForward;
    private float mSlowFactor = 1f;
    private int mVolume = 100;
    private boolean mMuted;

    private EcpSession(EcpPlayActivity activity, EcpEngine.Session session) {
        mActivity = activity;
        mIntentSession = session;
        mData = session.getContent();
        mPath = session.getPath();
    }

    /** A session for the intent's ECP extras, or null when it has none (level 0). */
    static EcpSession from(EcpPlayActivity activity, Intent intent) {
        EcpEngine.Session session = EcpEngine.Session.from(intent);
        return session == null ? null : new EcpSession(activity, session);
    }

    EcpEngine.Session intentSession() {
        return mIntentSession;
    }

    /** Connects and says hello off the UI thread. */
    void start() {
        Log.i(TAG, "Session " + mIntentSession.getId() + ": connecting to port " + mIntentSession.getPort());
        mWorker.execute(() -> {
            try {
                // The SDK runs requests one at a time on its own worker thread, so handlers may
                // block on the core; stop and onClosed come on a separate control thread.
                mEngine = new EcpEngine(descriptor(), this);
                mEngine.connect(mIntentSession);
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
            sendStopped("error", code, text, startupError);
        } else {
            sendStopped("user", null, null, null);
        }
        endSession(false);
    }

    void onActivityDestroyed() {
        if (!mStopping && mLaunched) sendStopped("user", null, null, null);
        mStopping = true;
        closeEngine();
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
                JSONObject frame = capture(0, 0, 1280);
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
    // EcpEngine.Callbacks: requests arrive on the SDK's worker thread and go through handleRequest.

    @Override public void handleLaunch(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handlePause(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleResume(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleSaveState(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleLoadState(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleListStates(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleDeleteState(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleGetSchema(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleGetSettings(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleSetSetting(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleReset(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleSetSpeed(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleScreenshot(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleSetVolume(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleHudLevel(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleNotify(EcpEngine.Request r) { handleRequest(r); }
    @Override public void handleSetRewind(EcpEngine.Request r) { r.unsupported(); }
    @Override public void handleSetCheats(EcpEngine.Request r) { r.unsupported(); }
    @Override public void handleListDiscs(EcpEngine.Request r) { r.unsupported(); }
    @Override public void handleSetDisc(EcpEngine.Request r) { r.unsupported(); }
    @Override public void onResult(long id, JSONObject msg) {}

    @Override
    public void onHello(JSONObject frontend) {
        onFrontendHello(frontend);
    }

    /** On the SDK's control thread, so a slow request never holds it up (5 s budget). */
    @Override
    public void handleStop(EcpEngine.Request r) {
        mStopping = true;
        shutdownVm();
        r.ok();
        sendStopped("frontend", null, null, null);
        endSession(true);
    }

    /** On the SDK's control thread. SPEC 4: without a frontend, save and exit within 5 s. */
    @Override
    public void onClosed(String reason) {
        Log.i(TAG, "Channel closed: " + reason);
        if (mStopping) return;
        shutdownVm();
        endSession(false);
    }

    private void closeEngine() {
        EcpEngine engine = mEngine;
        if (engine != null) engine.close();
    }

    private void onFrontendHello(JSONObject msg) {
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

    private void handleRequest(EcpEngine.Request r) {
        String type = r.getType();
        try {
            dispatch(type, r);
        } catch (Throwable t) {
            Log.e(TAG, type + " failed", t);
            r.error("internal", "PSX2 could not do that.", String.valueOf(t));
        }
    }

    private void dispatch(String type, EcpEngine.Request r) throws JSONException {
        if (!mHelloDone && !"launch".equals(type)) {
            r.error("protocol", null, "request before hello");
            return;
        }
        String needs = capabilityFor(type);
        // resume also ends the menu hand-over, so menu alone allows it (SPEC 6.2 step 5).
        boolean allowed = needs == null || mCaps.contains(needs)
                || "resume".equals(type) && mCaps.contains("menu");
        if (!allowed) {
            r.error("unsupported", null, type + " needs " + needs);
            return;
        }
        switch (type) {
            case "launch": doLaunch(r); break;
            case "pause": doPause(r); break;
            case "resume": doResume(r); break;
            case "save_state": doSaveState(r); break;
            case "load_state": doLoadState(r); break;
            case "list_states": doListStates(r); break;
            case "delete_state": doDeleteState(r); break;
            case "reset": doReset(r); break;
            case "set_speed": doSetSpeed(r); break;
            case "screenshot": doScreenshot(r); break;
            case "set_volume": doSetVolume(r); break;
            case "hud_level": doHudLevel(r); break;
            case "notify": doNotify(r); break;
            case "get_schema": r.ok(new JSONObject().put("settings", mSettings.schema())); break;
            case "get_settings": r.ok(new JSONObject().put("values", mSettings.values())); break;
            case "set_setting": doSetSetting(r); break;
            default: r.error("unsupported", null, "unknown request " + type);
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

    private void doLaunch(EcpEngine.Request r) throws JSONException {
        if (mLaunched) {
            r.error("busy", "A game is already running.", "second launch");
            return;
        }
        if (NativeApp.isVMActive() && !mActivity.isThread()) {
            r.error("busy", "PSX2 ECP is already running a game. Close it and try again.",
                    "VM owned by another activity");
            return;
        }

        String game = resolveContent(r.getMsg().optString("content"));
        if (game == null) {
            r.error("content_missing", "The game file could not be opened.",
                    "content " + r.getMsg().optString("content") + ", data " + mData + ", path " + mPath);
            return;
        }
        if (game.toLowerCase(Locale.ROOT).endsWith(".acgame")) {
            r.error("content_invalid", "Arcade sets can only be started from PSX2's own library.",
                    ".acgame needs a folder grant");
            return;
        }

        BiosVerifier.ScanResult scan = BiosVerifier.scanVerifiedBioses(mActivity);
        boolean hasBios = scan.byRegion.containsKey(BiosVerifier.Region.USA)
                || scan.byRegion.containsKey(BiosVerifier.Region.EUROPE)
                || scan.byRegion.containsKey(BiosVerifier.Region.JAPAN);
        if (!hasBios) {
            r.error("bios_missing",
                    "PSX2 ECP needs a PS2 BIOS dumped from your own console. Open PSX2 ECP from your "
                            + "app list, open its menu (top left) and choose Import / Manage BIOS.",
                    "no verified retail BIOS in " + BiosVerifier.getBiosDirectory(mActivity));
            return;
        }

        JSONObject settings = r.getMsg().optJSONObject("settings");
        if (settings != null) mLaunchSettings = settings;
        JSONArray chord = r.getMsg().optJSONArray("menu_chord");
        if (chord != null) mChord = chordKeys(chord);

        // A previous session's game in this activity: stop it before the new one boots.
        if (mActivity.isThread()) {
            NativeApp.shutdown();
            long until = SystemClock.uptimeMillis() + 5000;
            while (mActivity.isThread() && SystemClock.uptimeMillis() < until) sleep(50);
            if (mActivity.isThread()) {
                r.error("busy", "The previous game is still closing.", "VM did not stop");
                return;
            }
        }

        float[] perf = NativeApp.ecpGetPerf();
        mDrawnAtLaunch = perf != null && perf.length > 8 ? perf[8] : 0;
        mLaunched = true;
        r.ok();
        Log.i(TAG, "Launching " + r.getMsg().optString("title", game) + " (" + r.getMsg().optString("game_id") + ")");
        JSONObject loading = new JSONObject().put("type", "loading");
        String title = r.getMsg().optString("title");
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
        if (!alreadyStopped && mLaunched && !mStoppedSent && !mEnded) sendStopped("frontend", null, null, null);
        closeEngine();
        mActivity.runOnUiThread(mActivity::finishSession);
    }

    private void sendStopped(String reason, String code, String message, String detail) {
        synchronized (this) {
            if (mStoppedSent) return;
            mStoppedSent = true;
        }
        EcpEngine engine = mEngine;
        if (engine != null) engine.stopped(reason, code, message, TextUtils.isEmpty(detail) ? null : detail);
    }

    /** Runs every 50 ms once the game is launched: first frame, pause events, perf. */
    private void watch() {
        if (mStopping) return;
        try {
            if (!mFirstFrame) {
                float[] perf = NativeApp.ecpGetPerf();
                // The game is on screen once the GS has drawn into a couple of frames; the frame
                // counter alone also counts the black frames of the boot.
                if (perf != null && perf.length > 8 && perf[7] > 0 && perf[8] >= mDrawnAtLaunch + 2
                        && NativeApp.isVMActive()) {
                    mFirstFrame = true;
                    mEngine.firstFrame();
                    mEngine.running();
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

    private void doPause(EcpEngine.Request r) {
        mFrontendPause = true;
        mActivity.refreshPauseState("ecp pause");
        if (mFirstFrame) waitPaused(true, 1000);
        r.ok();
        syncPauseEvent();
    }

    private void doResume(EcpEngine.Request r) {
        mFrontendPause = false;
        mMenuOpen = false;
        ignoreHeldInput();
        mActivity.runOnUiThread(mActivity::bringToFront);
        mActivity.refreshPauseState("ecp resume");
        if (mFirstFrame) waitPaused(false, 2000);
        r.ok();
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
        EcpEngine engine = mEngine;
        if (!mFirstFrame || mStopping || mStateBusy || engine == null) return;
        synchronized (mPauseEventLock) {
            boolean paused = NativeApp.isPaused();
            if (paused == engine.isPausedReported()) return;
            if (paused) engine.paused(mMenuOpen ? "menu" : mFrontendPause ? "frontend" : "focus");
            else engine.resumed();
        }
    }

    private void waitPaused(boolean paused, long timeoutMs) {
        long until = SystemClock.uptimeMillis() + timeoutMs;
        while (NativeApp.isPaused() != paused && SystemClock.uptimeMillis() < until) sleep(10);
    }

    private void sendMenuIntent() {
        if (mIntentSession.getMenuIntent() != null)
            EcpEngine.sendMenuIntent(mActivity, mIntentSession.getMenuIntent());
    }

    // ---------------------------------------------------------------------------------------
    // States (SPEC 6.3). ECP slot 0 is PCSX2's resume slot (-1); slots 1-99 are its numbered slots,
    // so states saved here also appear in PSX2's own Save States list (slots 1-10).

    private static int coreSlot(int slot) {
        return slot == 0 ? -1 : slot;
    }

    private Integer slotOf(EcpEngine.Request r) {
        int slot = r.getInt("slot", -1);
        if (slot < 0 || slot > SLOT_MAX) {
            r.error("protocol", null, "slot must be 0-99");
            return null;
        }
        if (!mFirstFrame || !NativeApp.isVMActive()) {
            r.error("busy", "The game is still starting.", "no VM yet");
            return null;
        }
        return slot;
    }

    private void doSaveState(EcpEngine.Request r) throws JSONException {
        Integer slot = slotOf(r);
        if (slot == null) return;
        long started = System.currentTimeMillis();
        boolean ok;
        mStateBusy = true;
        try {
            ok = NativeApp.saveStateToSlot(coreSlot(slot));
            restorePauseState("ecp state saved");
        } finally {
            mStateBusy = false;
        }
        File file = stateFile(slot);
        ok = ok && file != null && file.isFile() && file.lastModified() >= started - 2000;
        if (!ok) {
            r.error("busy", "The state could not be saved. Try again in a moment.",
                    "saveStateToSlot failed or wrote nothing (memory card busy?)");
            return;
        }
        JSONObject entry = stateEntry(slot, file);
        if (mCaps.contains("state_thumbnails") && !entry.has("thumbnail")) {
            // PCSX2 stores no screenshot when the GS had none to give; try once more.
            JSONObject frame = capture(640, 480, 640);
            if (frame != null) entry.put("thumbnail", frame);
        }
        r.ok(entry);
    }

    private void doLoadState(EcpEngine.Request r) {
        Integer slot = slotOf(r);
        if (slot == null) return;
        File file = stateFile(slot);
        if (file == null || !file.isFile()) {
            r.error("slot_empty", "That slot is empty.", null);
            return;
        }
        boolean ok;
        mStateBusy = true;
        try {
            ok = NativeApp.loadStateFromSlot(coreSlot(slot));
            restorePauseState("ecp state loaded");
        } finally {
            mStateBusy = false;
        }
        if (!ok) {
            r.error("state_incompatible", "This state cannot be loaded.",
                    "loadStateFromSlot failed (other version, hardcore mode or memory card busy)");
            return;
        }
        r.ok();
    }

    /** saveStateToSlot and loadStateFromSlot leave the VM paused; go back to what was asked. */
    private void restorePauseState(String reason) {
        mActivity.refreshPauseState(reason);
        sleep(30);
        waitPaused(mFrontendPause || mMenuOpen || !mActivity.hasWindowFocus(), 500);
    }

    private void doListStates(EcpEngine.Request r) throws JSONException {
        JSONArray states = new JSONArray();
        if (NativeApp.isVMActive() && mFirstFrame) {
            for (int slot = 0; slot <= SLOT_MAX; slot++) {
                File file = stateFile(slot);
                if (file != null && file.isFile()) states.put(stateEntry(slot, file));
            }
        }
        r.ok(new JSONObject().put("states", states));
    }

    private void doDeleteState(EcpEngine.Request r) {
        Integer slot = slotOf(r);
        if (slot == null) return;
        if (!NativeApp.ecpDeleteStateSlot(coreSlot(slot))) {
            r.error("slot_empty", "That slot is empty.", null);
            return;
        }
        r.ok();
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
                    entry.put("thumbnail", Ecp.picture(png, bounds.outWidth, bounds.outHeight));
                }
            }
        }
        return entry;
    }

    // ---------------------------------------------------------------------------------------
    // Other controls (SPEC 6.5)

    private void doReset(EcpEngine.Request r) {
        if (!NativeApp.isVMActive()) {
            r.error("busy", "The game is still starting.", "no VM yet");
            return;
        }
        NativeApp.ecpReset();
        r.ok();
    }

    private void doSetSpeed(EcpEngine.Request r) {
        boolean ff = r.getBoolean("fast_forward", false);
        double factor = r.getDouble("factor", Double.NaN);
        boolean slow = !Double.isNaN(factor) && factor < 1;
        if ((ff || (!slow && !Double.isNaN(factor))) && !mCaps.contains("fast_forward")
                || slow && !mCaps.contains("slow_motion")) {
            r.error("unsupported", null, "set_speed needs fast_forward or slow_motion");
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
        r.ok();
    }

    private void applySpeed() {
        int mode = mFastForward ? 1 : mSlowFactor < 1f ? 2 : mSpeedLimit ? 0 : 3;
        NativeApp.ecpSetLimiterMode(mode);
    }

    private void doScreenshot(EcpEngine.Request r) throws JSONException {
        JSONObject image = capture(0, 0, 2560);
        if (image == null) {
            r.error("busy", "No picture is available yet.", "ecpCaptureFrame returned nothing");
            return;
        }
        r.ok(new JSONObject().put("image", image));
    }

    private void doSetVolume(EcpEngine.Request r) {
        if (r.getMsg().has("percent")) mVolume = Math.max(0, Math.min(100, r.getInt("percent", mVolume)));
        if (r.getMsg().has("mute")) mMuted = r.getBoolean("mute", false);
        applyVolume();
        r.ok();
    }

    private void applyVolume() {
        NativeApp.ecpSetVolume(mVolume, mMuted);
    }

    private void doHudLevel(EcpEngine.Request r) {
        NativeApp.ecpSetHudLevel(Math.max(0, Math.min(3, r.getInt("level", 0))));
        r.ok();
    }

    private void doNotify(EcpEngine.Request r) {
        String text = r.getMsg().optString("text");
        if (!text.isEmpty()) NativeApp.ecpNotify(text, (float) r.getDouble("seconds", 3));
        r.ok();
    }

    private void doSetSetting(EcpEngine.Request r) throws JSONException {
        String key = r.getMsg().optString("key");
        EcpSettings.Setting s = mSettings.get(key);
        if (s == null) {
            r.error("unsupported", null, "unknown setting " + key);
            return;
        }
        Object value = r.getMsg().opt("value");
        String problem = value == null ? "no value" : EcpSettings.validate(s, value);
        if (problem != null) {
            r.error("protocol", null, key + " " + problem);
            return;
        }
        s.setter.set(value);
        // Kept for the session only, so a restart of the VM in this session applies it again.
        mLaunchSettings.put(key, value);
        r.ok(new JSONObject().put("applied", s.applies));
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
        try {
            return Ecp.picture(bitmap);
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


    private void send(JSONObject message) {
        EcpEngine engine = mEngine;
        if (engine == null || !engine.send(message)) Log.w(TAG, "Unable to send " + message.optString("type"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
