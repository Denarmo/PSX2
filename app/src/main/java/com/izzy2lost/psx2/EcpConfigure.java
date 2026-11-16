package com.izzy2lost.psx2;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ECP `configure` (SPEC 6.6): a frontend sets PSX2 up with the player's BIOS, game folders and
 * settings, so PSX2 opened from the app list skips its setup wizard and lists the same games.
 * Reached from a session ({@link EcpSession}) and, outside one, from {@link EcpConfigureReceiver}.
 * It only adds: BIOS files are copied in beside the player's, and the game folders replace only
 * those an earlier configure set.
 */
final class EcpConfigure {
    private static final String TAG = "EcpConfigure";
    private static final String PREFS = "app_prefs";
    private static final String PREF_GAMES_FOLDER_URI = "games_folder_uri";
    private static final String PREF_GAMES_FOLDER_URIS = "games_folder_uris_json";
    /** The folders the last configure set, apart from those the player picked. */
    private static final String PREF_CONFIGURED_FOLDERS = "ecp_configured_game_folders_json";
    private static final long MAX_BIOS_BYTES = 32L << 20;
    private static final int MAX_DEPTH = 3;

    private EcpConfigure() {}

    /** Applies a configure request's fields; the answer is the result's fields. Any thread but the UI's. */
    static synchronized JSONObject apply(Context context, JSONObject request) throws JSONException {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray problems = new JSONArray();

        JSONArray bios = request.optJSONArray("bios");
        if (bios != null) {
            File dir = BiosVerifier.getBiosDirectory(app);
            for (int i = 0; i < bios.length(); i++) {
                String location = bios.optString(i, "");
                if (!location.isEmpty()) importBios(app, location, dir, 0);
            }
        }
        BiosVerifier.ScanResult scan = BiosVerifier.scanVerifiedBioses(app);
        boolean retailBios = scan.byRegion.containsKey(BiosVerifier.Region.USA)
                || scan.byRegion.containsKey(BiosVerifier.Region.EUROPE)
                || scan.byRegion.containsKey(BiosVerifier.Region.JAPAN);
        if (bios != null && !retailBios) {
            problems.put(io.github.ecp.Ecp.error("bios_missing",
                    "None of the BIOS files is a PS2 BIOS PSX2 recognises.", null));
        }

        int keptFolders = 0;
        JSONArray folders = request.optJSONArray("game_folders");
        if (folders != null) {
            keptFolders = setGameFolders(app, prefs, folders, problems);
        }

        int stored = 0;
        JSONObject settings = request.optJSONObject("settings");
        if (settings != null) stored = storeSettings(prefs, settings);

        boolean done = retailBios && !gameFolders(prefs, PREF_GAMES_FOLDER_URIS).isEmpty();
        if (done) {
            prefs.edit().putBoolean("first_run_done", true)
                    .putBoolean("has_opened_games_after_setup", true).apply();
        }
        Log.i(TAG, "BIOS " + scan.all.size() + ", folders " + keptFolders + ", settings " + stored
                + ", setup " + (done ? "done" : "not done"));
        return new JSONObject().put("bios", scan.all.size()).put("game_folders", keptFolders)
                .put("settings", stored).put("setup_done", done).put("problems", problems);
    }

    // --- BIOS ---------------------------------------------------------------------------------

    private static void importBios(Context context, String location, File dir, int depth) {
        Uri uri = Uri.parse(location);
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            DocumentFile doc = DocumentsContract.isTreeUri(uri)
                    ? DocumentFile.fromTreeUri(context, uri) : DocumentFile.fromSingleUri(context, uri);
            if (doc != null) importDocument(context, doc, dir, depth);
            return;
        }
        File file = "file".equals(uri.getScheme()) ? new File(uri.getPath()) : new File(location);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null || depth >= MAX_DEPTH) return;
            for (File child : children) importBios(context, child.getAbsolutePath(), dir, depth + 1);
        } else if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                importStream(context, in, file.getName(), file.length(), dir);
            } catch (Exception e) {
                Log.w(TAG, "Unable to read " + file, e);
            }
        }
    }

    private static void importDocument(Context context, DocumentFile doc, File dir, int depth) {
        try {
            if (doc.isDirectory()) {
                if (depth >= MAX_DEPTH) return;
                for (DocumentFile child : doc.listFiles()) importDocument(context, child, dir, depth + 1);
            } else if (doc.isFile()) {
                try (InputStream in = context.getContentResolver().openInputStream(doc.getUri())) {
                    if (in != null) importStream(context, in, doc.getName(), doc.length(), dir);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to read " + doc.getUri(), e);
        }
    }

    /** Copies one file in and keeps it only when it is a BIOS PSX2 verifies, as its own import does. */
    private static void importStream(Context context, InputStream in, String name, long size, File dir)
            throws Exception {
        if (size <= 0 || size > MAX_BIOS_BYTES) return;
        String safe = TextUtils.isEmpty(name) ? "bios.bin" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File target = new File(dir, safe);
        if (target.isFile() && target.length() == size) return; // already there
        if (target.exists()) target = new File(dir, System.currentTimeMillis() + "-" + safe);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File part = new File(dir, "." + safe + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_BIOS_BYTES) throw new java.io.IOException("too large");
                out.write(buffer, 0, n);
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        }
        // Verify under the final name: the verifier caches by path.
        if (!part.renameTo(target)) {
            part.delete();
            return;
        }
        if (BiosVerifier.verifyFile(context, dir, target) == null) {
            target.delete();
        } else {
            Log.i(TAG, "BIOS imported: " + target.getName());
        }
    }

    // --- Game folders -------------------------------------------------------------------------

    private static int setGameFolders(Context context, SharedPreferences prefs, JSONArray given, JSONArray problems) {
        ContentResolver resolver = context.getContentResolver();
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < given.length(); i++) {
            String location = given.optString(i, "");
            Uri uri = Uri.parse(location);
            if (location.isEmpty() || !ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                    || !DocumentsContract.isTreeUri(uri)) {
                continue; // PSX2's library reads document trees only
            }
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!kept.contains(location)) kept.add(location);
            } catch (SecurityException e) {
                Log.w(TAG, "No persistable grant for " + location, e);
                problems.put(io.github.ecp.Ecp.error("content_missing",
                        "PSX2 was not allowed to keep a game folder.", location));
            }
        }

        List<String> previous = gameFolders(prefs, PREF_CONFIGURED_FOLDERS);
        Set<String> all = new LinkedHashSet<>(gameFolders(prefs, PREF_GAMES_FOLDER_URIS));
        String legacy = prefs.getString(PREF_GAMES_FOLDER_URI, null);
        if (!TextUtils.isEmpty(legacy)) all.add(legacy);
        List<String> before = new ArrayList<>(all);
        all.removeAll(previous);
        all.addAll(kept);
        List<String> after = new ArrayList<>(all);

        SharedPreferences.Editor editor = prefs.edit()
                .putString(PREF_GAMES_FOLDER_URIS, new JSONArray(after).toString())
                .putString(PREF_CONFIGURED_FOLDERS, new JSONArray(kept).toString());
        if (after.isEmpty()) editor.remove(PREF_GAMES_FOLDER_URI);
        else editor.putString(PREF_GAMES_FOLDER_URI, after.get(0));
        // The cached list is of the old folders; the library scans again.
        if (!after.equals(before)) editor.remove("games_list_json");
        editor.apply();

        // Grants of folders a configure set and no longer names go back.
        for (String old : previous) {
            if (all.contains(old)) continue;
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                if (permission.getUri().toString().equals(old)) {
                    try {
                        resolver.releasePersistableUriPermission(permission.getUri(),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {}
                }
            }
        }
        return kept.size();
    }

    private static List<String> gameFolders(SharedPreferences prefs, String key) {
        List<String> out = new ArrayList<>();
        String json = prefs.getString(key, null);
        if (TextUtils.isEmpty(json)) return out;
        try {
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length(); i++) {
                String value = list.optString(i, "");
                if (!value.isEmpty() && !out.contains(value)) out.add(value);
            }
        } catch (JSONException ignored) {}
        return out;
    }

    // --- Settings -----------------------------------------------------------------------------

    /** The keys of EcpSettings that PSX2's own settings drawer keeps, in its preferences. */
    private static int storeSettings(SharedPreferences prefs, JSONObject settings) {
        SharedPreferences.Editor editor = prefs.edit();
        int stored = 0;
        for (Iterator<String> keys = settings.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = settings.opt(key);
            String text = String.valueOf(value);
            switch (key) {
                case "video.renderer":
                    editor.putInt("renderer", "vulkan".equals(text) ? 14 : "opengl".equals(text) ? 12
                            : "software".equals(text) ? 13 : -1);
                    break;
                case "video.aspect":
                    editor.putInt("aspect_ratio", "stretch".equals(text) ? 0 : "4:3".equals(text) ? 2
                            : "16:9".equals(text) ? 3 : 1);
                    break;
                case "video.internal_scale":
                    editor.putFloat("upscale_multiplier", Math.max(1, Math.min(8, EcpSettings.asInt(value))));
                    break;
                case "video.widescreen_patches":
                    editor.putBoolean("widescreen_patches", EcpSettings.asBool(value));
                    break;
                case "video.vsync":
                    editor.putBoolean("vsync_enabled", EcpSettings.asBool(value));
                    break;
                case "psx2.blending_accuracy":
                    editor.putInt("blending_accuracy", Math.max(0, Math.min(5, EcpSettings.asInt(value))));
                    break;
                case "psx2.no_interlacing_patches":
                    editor.putBoolean("no_interlacing_patches", EcpSettings.asBool(value));
                    break;
                case "psx2.edge_crop":
                    editor.putInt("edge_crop", Math.max(0, Math.min(16, EcpSettings.asInt(value))));
                    break;
                case "psx2.texture_replacements":
                    editor.putBoolean("load_textures", EcpSettings.asBool(value));
                    break;
                default:
                    continue; // session-only here, or not PSX2's
            }
            ++stored;
        }
        editor.apply();
        return stored;
    }
}
