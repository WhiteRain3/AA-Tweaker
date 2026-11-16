package sksa.aa.tweaker;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;
import android.system.StructStat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal stand-in for the sqlite3 command line tool, run as root through app_process:
 *
 *   CLASSPATH=/path/to/base.apk app_process /system/bin sksa.aa.tweaker.RootSql [-options] DB "SQL"
 *
 * It replaces the 32-bit sqlite3 binary the app used to bundle, which cannot run on
 * 64-bit-only phones. It uses the platform's own SQLite, so there is no native code to ship.
 * Output mimics sqlite3's default list mode: one row per line, columns separated by '|'.
 *
 * This class runs outside the app process: no Context, no resources, no app classes.
 */
public final class RootSql {

    private static final int MAX_BLOB_BYTES = 48;

    /** -header: print column names before the rows of each query, like sqlite3 -header. */
    private static boolean header;

    private RootSql() {
    }

    public static void main(String[] args) {
        int exit = 0;
        try {
            String db = null;
            StringBuilder sql = new StringBuilder();
            for (String a : args) {
                if (db == null) {
                    if (a.equals("-header")) header = true;
                    if (a.startsWith("-")) continue; // other sqlite3 options such as -batch are ignored
                    db = a;
                } else {
                    if (sql.length() > 0) sql.append(' ');
                    sql.append(a);
                }
            }
            if (db == null) {
                System.err.println("Error: usage: RootSql [-options] DATABASE SQL");
                System.exit(2);
                return;
            }
            exit = run(db, split(sql.toString())) ? 0 : 1;
        } catch (Throwable t) {
            System.err.println("Error: " + t);
            exit = 1;
        }
        System.out.flush();
        System.err.flush();
        System.exit(exit);
    }

    private static boolean run(String path, List<String> statements) throws Exception {
        if (!new File(path).exists()) {
            System.err.println("Error: unable to open database \"" + path + "\": no such file");
            return false;
        }
        boolean readOnly = true;
        for (String s : statements) {
            if (!isQuery(s)) readOnly = false;
        }

        int flags = SQLiteDatabase.NO_LOCALIZED_COLLATORS;
        if (readOnly) {
            flags |= SQLiteDatabase.OPEN_READONLY;
        } else {
            flags |= SQLiteDatabase.OPEN_READWRITE;
            // Keep whatever journal mode the owning app uses instead of letting the framework change it.
            if ("wal".equalsIgnoreCase(journalMode(path))) {
                flags |= SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
            }
        }

        StructStat owner = Os.stat(path);
        boolean ok = true;
        SQLiteDatabase db = SQLiteDatabase.openDatabase(path, null, flags);
        try {
            for (String s : statements) {
                try {
                    if (isQuery(s)) {
                        print(db.rawQuery(s, null));
                    } else {
                        db.execSQL(s);
                    }
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                    ok = false;
                    break; // like sqlite3 -batch: stop at the first error
                }
            }
        } finally {
            db.close();
            if (!readOnly) restoreOwnership(path, owner);
        }
        return ok;
    }

    private static String journalMode(String path) {
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(path, null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            c = db.rawQuery("PRAGMA journal_mode", null);
            return c.moveToFirst() ? c.getString(0) : "";
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    /** Journal files created while root had the database open must stay readable by its real owner. */
    private static void restoreOwnership(String path, StructStat owner) {
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File f = new File(path + suffix);
            if (!f.exists()) continue;
            try {
                Os.chown(f.getPath(), owner.st_uid, owner.st_gid);
            } catch (Exception e) {
                System.err.println("Warning: could not restore owner of " + f + ": " + e.getMessage());
            }
        }
    }

    private static void print(Cursor c) {
        try {
            int n = c.getColumnCount();
            StringBuilder line = new StringBuilder();
            if (header) {
                for (int i = 0; i < n; i++) {
                    if (i > 0) line.append('|');
                    line.append(c.getColumnName(i));
                }
                System.out.println(line);
            }
            while (c.moveToNext()) {
                line.setLength(0);
                for (int i = 0; i < n; i++) {
                    if (i > 0) line.append('|');
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL:
                            break;
                        case Cursor.FIELD_TYPE_BLOB:
                            appendBlob(line, c.getBlob(i));
                            break;
                        default:
                            line.append(c.getString(i));
                    }
                }
                System.out.println(line);
            }
        } finally {
            c.close();
        }
    }

    /** Blobs are shown as a hex literal, shortened, so binary data cannot garble the output. */
    private static void appendBlob(StringBuilder line, byte[] blob) {
        line.append("x'");
        for (int i = 0; i < blob.length && i < MAX_BLOB_BYTES; i++) {
            line.append(String.format(Locale.ROOT, "%02x", blob[i] & 0xff));
        }
        line.append('\'');
        if (blob.length > MAX_BLOB_BYTES) line.append("(+").append(blob.length - MAX_BLOB_BYTES).append(" bytes)");
    }

    static boolean isQuery(String statement) {
        String s = statement.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("SELECT") || s.startsWith("EXPLAIN")) return true;
        return s.startsWith("PRAGMA") && s.indexOf('=') < 0;
    }

    /**
     * Splits a script into statements. Understands quoting and keeps a CREATE TRIGGER body
     * (which contains its own semicolons) together until its closing END.
     * Double-quoted tokens are rewritten as single-quoted string literals: this app's SQL
     * uses them only as strings, and newer SQLite builds may reject that spelling.
     */
    static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = script.length();
        for (int i = 0; i < n; i++) {
            char ch = script.charAt(i);
            if (ch == '\'') {
                int end = closing(script, i, '\'');
                cur.append(script, i, end + 1);
                i = end;
            } else if (ch == '"') {
                int end = closing(script, i, '"');
                String inner = script.substring(i + 1, Math.min(end, n)).replace("\"\"", "\"");
                cur.append('\'').append(inner.replace("'", "''")).append('\'');
                i = end;
            } else if (ch == ';') {
                String s = cur.toString().trim();
                if (isTrigger(s) && !endsWithEnd(s)) {
                    cur.append(ch);
                } else {
                    if (!s.isEmpty()) out.add(s);
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    /** Index of the quote that closes the one at {@code start}; doubled quotes are escapes. */
    private static int closing(String s, int start, char q) {
        int n = s.length();
        for (int i = start + 1; i < n; i++) {
            if (s.charAt(i) == q) {
                if (i + 1 < n && s.charAt(i + 1) == q) {
                    i++;
                } else {
                    return i;
                }
            }
        }
        return n - 1;
    }

    private static boolean isTrigger(String s) {
        return s.toUpperCase(Locale.ROOT).matches("(?s)^CREATE\\s+(TEMP\\s+|TEMPORARY\\s+)?TRIGGER\\b.*");
    }

    private static boolean endsWithEnd(String s) {
        return s.toUpperCase(Locale.ROOT).matches("(?s).*[;\\s]END$");
    }
}
