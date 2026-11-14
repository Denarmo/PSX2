package com.izzy2lost.psx2;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings PSX2 publishes over ECP (SPEC 6.4): the standard keys it has, plus its own under
 * "psx2.". Every value goes to the core's in-memory settings layer through the same native
 * setters PSX2's menus use, but nothing is written to SharedPreferences, so a session leaves the
 * player's own PSX2 settings untouched.
 */
final class EcpSettings {
    interface Getter { Object get(); }
    interface Setter { void set(Object value); }

    static final class Setting {
        final String key, label, type, group;
        String help = "", applies = "now";
        String[][] choices;
        double min, max, step = 1;
        Object def;
        Getter getter;
        Setter setter;

        Setting(String key, String label, String type, String group) {
            this.key = key;
            this.label = label;
            this.type = type;
            this.group = group;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("label", label);
            if (!help.isEmpty()) o.put("help", help);
            o.put("type", type);
            if (choices != null) {
                JSONArray list = new JSONArray();
                for (String[] c : choices) list.put(new JSONArray().put(c[0]).put(c[1]));
                o.put("choices", list);
            }
            if ("range".equals(type)) {
                o.put("min", min);
                o.put("max", max);
                o.put("step", step);
            }
            if (def != null) o.put("default", def);
            o.put("group", group);
            o.put("applies", applies);
            return o;
        }
    }

    /** Session-only speed and volume state; owned by EcpSession. */
    interface SessionState {
        boolean speedLimit();
        void setSpeedLimit(boolean on);
        int volume();
        void setVolume(int percent);
    }

    private final Map<String, Setting> mSettings = new LinkedHashMap<>();

    EcpSettings(SessionState session) {
        Setting s;

        s = add("video.internal_scale", "Internal resolution", "range", "video");
        s.help = "Renders at the PS2's resolution times this. Higher is sharper and slower.";
        s.min = 1; s.max = 8; s.def = 1;
        s.getter = () -> Math.round(parseFloat(ini("EmuCore/GS", "upscale_multiplier", "1"), 1f));
        s.setter = v -> NativeApp.renderUpscalemultiplier(clamp(asInt(v), 1, 8));

        s = add("video.aspect", "Aspect ratio", "choice", "video");
        s.choices = new String[][] {{"auto", "Auto (4:3/3:2)"}, {"4:3", "4:3"}, {"16:9", "16:9"}, {"stretch", "Stretch"}};
        s.def = "auto";
        s.getter = () -> {
            String a = ini("EmuCore/GS", "AspectRatio", "Auto 4:3/3:2");
            if ("4:3".equals(a)) return "4:3";
            if ("16:9".equals(a)) return "16:9";
            if ("Stretch".equals(a)) return "stretch";
            return "auto";
        };
        s.setter = v -> {
            String a = String.valueOf(v);
            int type = "stretch".equals(a) ? 0 : "4:3".equals(a) ? 2 : "16:9".equals(a) ? 3 : 1;
            NativeApp.setAspectRatio(type);
        };

        s = add("video.widescreen_patches", "Widescreen patches", "toggle", "video");
        s.help = "Applies the game's widescreen patch when PSX2 has one. Pair with 16:9.";
        s.def = true;
        s.getter = () -> parseBool(ini("EmuCore", "EnableWideScreenPatches", "false"));
        s.setter = v -> NativeApp.setWidescreenPatches(asBool(v));

        s = add("video.renderer", "Renderer", "choice", "video");
        s.choices = new String[][] {{"auto", "Automatic"}, {"vulkan", "Vulkan"}, {"opengl", "OpenGL"}, {"software", "Software"}};
        s.def = "auto";
        s.getter = () -> {
            String r = ini("EmuCore/GS", "Renderer", "-1");
            if ("14".equals(r) || "Vulkan".equalsIgnoreCase(r)) return "vulkan";
            if ("12".equals(r) || "OpenGL".equalsIgnoreCase(r)) return "opengl";
            if ("13".equals(r) || "Software".equalsIgnoreCase(r)) return "software";
            return "auto";
        };
        s.setter = v -> {
            String r = String.valueOf(v);
            NativeApp.renderGpu("vulkan".equals(r) ? 14 : "opengl".equals(r) ? 12 : "software".equals(r) ? 13 : -1);
        };

        s = add("video.texture_filter", "Texture filtering", "choice", "video");
        s.choices = new String[][] {{"nearest", "Nearest"}, {"linear", "Bilinear (forced)"},
                {"ps2", "Bilinear (PS2)"}, {"linear_no_sprites", "Bilinear (forced, not sprites)"}};
        s.def = "ps2";
        s.getter = () -> {
            switch (ini("EmuCore/GS", "filter", "2")) {
                case "0": return "nearest";
                case "1": return "linear";
                case "3": return "linear_no_sprites";
                default: return "ps2";
            }
        };
        s.setter = v -> {
            String f = String.valueOf(v);
            int value = "nearest".equals(f) ? 0 : "linear".equals(f) ? 1 : "linear_no_sprites".equals(f) ? 3 : 2;
            NativeApp.ecpSetIniValue("EmuCore/GS", "filter", Integer.toString(value));
        };

        s = add("video.vsync", "VSync", "toggle", "video");
        s.def = false;
        s.getter = () -> parseBool(ini("EmuCore/GS", "VsyncEnable", "false"));
        s.setter = v -> NativeApp.setVsyncEnabled(asBool(v));

        s = add("audio.volume", "Volume", "range", "audio");
        s.min = 0; s.max = 100; s.step = 5; s.def = 100;
        s.getter = session::volume;
        s.setter = v -> session.setVolume(clamp(asInt(v), 0, 100));

        s = add("speed.limit", "Limit speed", "toggle", "speed");
        s.help = "Off runs the game as fast as the device can.";
        s.def = true;
        s.getter = session::speedLimit;
        s.setter = v -> session.setSpeedLimit(asBool(v));

        s = add("speed.fast_forward_factor", "Fast-forward speed", "range", "speed");
        s.min = 2; s.max = 10; s.def = 2;
        s.getter = () -> Math.round(parseFloat(ini("Framerate", "TurboScalar", "2"), 2f));
        s.setter = v -> NativeApp.ecpSetIniValue("Framerate", "TurboScalar", Integer.toString(clamp(asInt(v), 2, 10)));

        s = add("psx2.blending_accuracy", "Blending accuracy", "choice", "psx2");
        s.help = "Higher fixes more effects and costs more GPU time.";
        s.choices = new String[][] {{"0", "Minimum"}, {"1", "Basic"}, {"2", "Medium"}, {"3", "High"}, {"4", "Full"}, {"5", "Maximum"}};
        s.def = "1";
        s.getter = () -> ini("EmuCore/GS", "accurate_blending_unit", "1");
        s.setter = v -> NativeApp.setBlendingAccuracy(clamp(asInt(v), 0, 5));

        s = add("psx2.anisotropic_filtering", "Anisotropic filtering", "choice", "psx2");
        s.choices = new String[][] {{"0", "Off"}, {"2", "2x"}, {"4", "4x"}, {"8", "8x"}, {"16", "16x"}};
        s.def = "0";
        s.getter = () -> ini("EmuCore/GS", "MaxAnisotropy", "0");
        s.setter = v -> NativeApp.ecpSetIniValue("EmuCore/GS", "MaxAnisotropy", Integer.toString(asInt(v)));

        s = add("psx2.no_interlacing_patches", "No-interlacing patches", "toggle", "psx2");
        s.def = true;
        s.getter = () -> parseBool(ini("EmuCore", "EnableNoInterlacingPatches", "false"));
        s.setter = v -> NativeApp.setNoInterlacingPatches(asBool(v));

        s = add("psx2.fxaa", "FXAA", "toggle", "psx2");
        s.def = false;
        s.getter = () -> parseBool(ini("EmuCore/GS", "fxaa", "false"));
        s.setter = v -> NativeApp.ecpSetIniValue("EmuCore/GS", "fxaa", asBool(v) ? "true" : "false");

        s = add("psx2.edge_crop", "Edge crop", "range", "psx2");
        s.help = "Native pixels hidden at the left and right edges.";
        s.min = 0; s.max = 16; s.step = 4; s.def = MainActivity.DEFAULT_EDGE_CROP;
        s.getter = () -> parseInt(ini("EmuCore/GS", "CropLeft", "0"), 0);
        s.setter = v -> NativeApp.setEdgeCrop(clamp(asInt(v), 0, 16));

        s = add("psx2.texture_replacements", "Texture replacements", "toggle", "psx2");
        s.help = "Loads texture packs installed in PSX2.";
        s.def = false;
        s.getter = () -> parseBool(ini("EmuCore/GS", "LoadTextureReplacements", "false"));
        s.setter = v -> NativeApp.setLoadTextures(asBool(v));

        s = add("psx2.shade_boost", "Shade boost", "toggle", "psx2");
        s.def = false;
        s.getter = () -> parseBool(ini("EmuCore/GS", "ShadeBoost", "false"));
        s.setter = v -> NativeApp.setShadeBoost(asBool(v));
    }

    private Setting add(String key, String label, String type, String group) {
        Setting s = new Setting(key, label, type, group);
        mSettings.put(key, s);
        return s;
    }

    Setting get(String key) {
        return mSettings.get(key);
    }

    List<Setting> all() {
        return Collections.unmodifiableList(new ArrayList<>(mSettings.values()));
    }

    JSONArray schema() throws JSONException {
        JSONArray list = new JSONArray();
        for (Setting s : mSettings.values()) list.put(s.toJson());
        return list;
    }

    JSONObject values() throws JSONException {
        JSONObject values = new JSONObject();
        for (Setting s : mSettings.values()) {
            try {
                values.put(s.key, s.getter.get());
            } catch (RuntimeException e) {
                android.util.Log.w("EcpSettings", "Unable to read " + s.key, e);
            }
        }
        return values;
    }

    /** Checks a value against the setting's type; returns null when it fits. */
    static String validate(Setting s, Object value) {
        switch (s.type) {
            case "toggle":
                return value instanceof Boolean ? null : "expects true or false";
            case "range":
                if (!(value instanceof Number)) return "expects a number";
                double d = ((Number) value).doubleValue();
                return (d < s.min || d > s.max) ? "expects " + (long) s.min + "-" + (long) s.max : null;
            case "choice":
                String v = String.valueOf(value);
                for (String[] c : s.choices) if (c[0].equals(v)) return null;
                return "is not one of the choices";
            default:
                return null;
        }
    }

    /** True when the core already holds this value, so applying it again is not needed. */
    static boolean holds(Setting s, Object value) {
        try {
            Object current = s.getter.get();
            if (current instanceof Number && value instanceof Number)
                return ((Number) current).doubleValue() == ((Number) value).doubleValue();
            return String.valueOf(current).equals(String.valueOf(value));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String ini(String section, String key, String def) {
        String v = NativeApp.ecpGetIniValue(section, key, def);
        return v == null ? def : v;
    }

    static boolean asBool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        return parseBool(String.valueOf(v));
    }

    static int asInt(Object v) {
        if (v instanceof Number) return (int) Math.round(((Number) v).doubleValue());
        return parseInt(String.valueOf(v), 0);
    }

    private static boolean parseBool(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static int parseInt(String s, int def) {
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float parseFloat(String s, float def) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
