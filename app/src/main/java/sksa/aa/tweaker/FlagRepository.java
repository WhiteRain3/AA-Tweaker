package sksa.aa.tweaker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines three sources into one list of flags:
 *  - the bundled catalog (plain-English name, description, risk),
 *  - an AA Experiments export the user imported (every flag in their build, defaults, overrides),
 *  - the values Google's server delivered, read from Play Services' phenotype.db with root.
 * Also stores the user's own notes per flag.
 */
public final class FlagRepository {

    public static final String[] CATEGORIES = {"layout", "look", "media", "notifications", "messaging",
            "assistant", "navigation", "wireless", "apps", "input", "restrictions", "system", "other"};

    public static final class Flag {
        public String name;
        public String category = "other";
        public String title;
        public String description;
        public String risk;          // safe, may_break, experimental, safety, or null when unknown
        public String suggest;
        public String verified;
        public String type;
        public String defaultValue;  // built into the Android Auto app
        public String serverValue;   // delivered by Google
        public String override;      // set by the user in AA Experiments
        public boolean editable = true;

        public boolean described() {
            return description != null;
        }

        /** What Android Auto is using: the user's override, else Google's value, else the default. */
        public String effective() {
            if (override != null) return override;
            if (serverValue != null) return serverValue;
            return defaultValue;
        }
    }

    public static final class Note {
        public String status = "";   // "", works, no_effect, broken
        public String text = "";
        public String aaVersion = "";
    }

    private static final String EXPORT_FILE = "aa-experiments-export.json";
    private static final String NOTES = "flag_notes";

    private final Context context;

    public FlagRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Loads everything that does not need root. */
    public Map<String, Flag> load() {
        Map<String, Flag> flags = new LinkedHashMap<>();
        try {
            JSONArray catalog = new JSONObject(readAsset("flags.json")).getJSONArray("flags");
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject o = catalog.getJSONObject(i);
                Flag f = get(flags, o.getString("name"));
                f.category = o.optString("category", "other");
                f.title = o.optString("title", null);
                f.description = o.optString("description", null);
                f.risk = o.optString("risk", null);
                f.suggest = o.optString("suggest", null);
                f.verified = o.optString("verified", null);
            }
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Bundled flag catalog is unreadable", e);
        }
        File export = exportFile();
        if (export.isFile()) {
            try (InputStream in = new FileInputStream(export)) {
                mergeExport(flags, new String(readAll(in), "UTF-8"));
            } catch (IOException | JSONException ignored) {
                // A broken import is ignored; the user can import again.
            }
        }
        return flags;
    }

    /** Adds Google's server-delivered values. Needs root; returns a short status line. */
    public String mergeServerValues(Map<String, Flag> flags) {
        String sqlite = Root.sqlite(context);
        int count = 0;
        StringBuilder errors = new StringBuilder();
        for (String pkg : new String[]{AndroidAuto.PACKAGE, "com.google.android.gms.car"}) {
            StreamLogs logs = Root.run(sqlite + " -flags " + DiagnosticsActivity.PHENOTYPE_DB + " " + Root.quote(pkg));
            for (String line : logs.getInputStreamLog().split("\n")) {
                String[] p = line.split("\\|", 3);
                if (p.length < 3) continue;
                Flag f = get(flags, p[1]);
                f.serverValue = p[2];
                if (f.type == null) f.type = typeName(p[0]);
                count++;
            }
            if (logs.getInputStreamLog().isEmpty() && !logs.getErrorStreamLog().isEmpty()) {
                errors.append(logs.getErrorStreamLog()).append('\n');
            }
        }
        return count > 0 ? count + " values set by Google" : "Could not read Google's values: " + errors.toString().trim();
    }

    /** Stores an AA Experiments "editable catalog" export. Returns the number of flags in it. */
    public int importExport(InputStream in) throws IOException, JSONException {
        String json = new String(readAll(in), "UTF-8");
        int n = mergeExport(new LinkedHashMap<>(), json); // validates before saving
        try (FileOutputStream out = new FileOutputStream(exportFile())) {
            out.write(json.getBytes("UTF-8"));
        }
        return n;
    }

    public boolean hasExport() {
        return exportFile().isFile();
    }

    private int mergeExport(Map<String, Flag> flags, String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        int n = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            String key = o.optString("key", "");
            if (key.isEmpty()) continue;
            Flag f = get(flags, key);
            f.type = o.optString("valueType", f.type == null ? "" : f.type).toLowerCase(java.util.Locale.ROOT);
            f.defaultValue = o.isNull("compiledDefault") ? null : o.optString("compiledDefault");
            f.override = o.isNull("override") ? null : o.optString("override");
            f.editable = o.optBoolean("editable", true);
            n++;
        }
        if (n == 0) throw new JSONException("No flags found in this file");
        return n;
    }

    public static List<Flag> sorted(Map<String, Flag> flags) {
        List<Flag> list = new ArrayList<>(flags.values());
        Collections.sort(list, (a, b) -> {
            if (a.described() != b.described()) return a.described() ? -1 : 1;
            int c = Integer.compare(categoryIndex(a.category), categoryIndex(b.category));
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        return list;
    }

    private static int categoryIndex(String category) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(category)) return i;
        }
        return CATEGORIES.length;
    }

    // ---- notes ---------------------------------------------------------------------------

    public Note note(String flag) {
        Note n = new Note();
        String raw = prefs().getString(flag, null);
        if (raw == null) return n;
        try {
            JSONObject o = new JSONObject(raw);
            n.status = o.optString("status", "");
            n.text = o.optString("text", "");
            n.aaVersion = o.optString("aa", "");
        } catch (JSONException ignored) {
        }
        return n;
    }

    public void saveNote(String flag, Note note) {
        if (note.status.isEmpty() && note.text.isEmpty()) {
            prefs().edit().remove(flag).apply();
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("status", note.status);
            o.put("text", note.text);
            o.put("aa", note.aaVersion);
            prefs().edit().putString(flag, o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public boolean hasNote(String flag) {
        return prefs().contains(flag);
    }

    /** All notes as JSON, for copying out of the app. */
    public String exportNotes() {
        JSONObject all = new JSONObject();
        try {
            for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                all.put(e.getKey(), new JSONObject(String.valueOf(e.getValue())));
            }
            return all.toString(1);
        } catch (JSONException e) {
            return "{}";
        }
    }

    // ---- plumbing ------------------------------------------------------------------------

    private SharedPreferences prefs() {
        return context.getSharedPreferences(NOTES, Context.MODE_PRIVATE);
    }

    private File exportFile() {
        return new File(context.getFilesDir(), EXPORT_FILE);
    }

    private static Flag get(Map<String, Flag> flags, String name) {
        Flag f = flags.get(name);
        if (f == null) {
            f = new Flag();
            f.name = name;
            flags.put(name, f);
        }
        return f;
    }

    private static String typeName(String code) {
        switch (code) {
            case "B":
                return "boolean";
            case "L":
                return "long";
            case "D":
                return "double";
            case "S":
                return "string";
            default:
                return "bytes";
        }
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            return new String(readAll(in), "UTF-8");
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
