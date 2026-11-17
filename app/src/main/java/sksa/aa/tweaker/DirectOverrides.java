package sksa.aa.tweaker;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Experimental: writes Android Auto flag overrides straight into Play Services' phenotype.db
 * (table flag_overrides, the layout used by Play Services 26.x).
 *
 * The row format (account 0, active 1, type 1 long / 2 boolean / 3 double / 4 string, package
 * referenced through config_packages) follows the approach proposed by dixxi1208 in
 * shmykelsa/AA-Tweaker pull request 93. Whether Play Services honours rows written this way
 * has to be confirmed on each device, which is why every write is backed up first and only
 * rows for the Android Auto flag sets are ever touched.
 */
public final class DirectOverrides {

    public static final String[] PACKAGES = {AndroidAuto.PACKAGE, "com.google.android.gms.car"};
    public static final String[] TYPES = {"boolean", "long", "double", "string"};

    private static final String DB = DiagnosticsActivity.PHENOTYPE_DB;
    private static final String DB_DIR = "/data/data/com.google.android.gms/databases";
    private static final String OUR_PACKAGES = "'" + PACKAGES[0] + "', '" + PACKAGES[1] + "'";
    private static final int KEEP_BACKUPS = 10;

    /** Rows of flag_overrides that belong to the Android Auto flag sets, for all accounts. */
    private static final String OUR_ROWS =
            "SELECT o.override_id FROM flag_overrides o "
                    + "LEFT JOIN config_packages p ON p.config_package_id = o.config_package_id "
                    + "WHERE p.name IN (" + OUR_PACKAGES + ") OR o.config_package_name IN (" + OUR_PACKAGES + ")";

    public static final class Row {
        public String pkg;
        public String name;
        public String type;
        public String value;
    }

    private DirectOverrides() {
    }

    /** True when this Play Services has the flag_overrides table this class writes to. */
    public static boolean supported(Context context) {
        return sql(context, "", "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'flag_overrides';")
                .contains("flag_overrides");
    }

    public static List<Row> list(Context context) {
        String out = Root.run(Root.sqlite(context) + " " + DB + " " + Root.quote(
                "SELECT COALESCE(p.name, o.config_package_name), o.name, o.type, o.value FROM flag_overrides o "
                        + "LEFT JOIN config_packages p ON p.config_package_id = o.config_package_id "
                        + "WHERE o.override_id IN (" + OUR_ROWS + ") ORDER BY o.name;")).getInputStreamLog();
        List<Row> rows = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] f = line.split("\\|", 4);
            if (f.length < 4) continue;
            Row r = new Row();
            r.pkg = f[0];
            r.name = f[1];
            r.type = typeName(f[2]);
            r.value = "boolean".equals(r.type) ? ("1".equals(f[3]) ? "true" : "false") : f[3];
            rows.add(r);
        }
        return rows;
    }

    /** Returns null when the input is fine, otherwise what is wrong with it. */
    public static String validate(String name, String type, String value) {
        if (!name.matches("[A-Za-z0-9_.:-]+")) return "Flag name may only contain letters, digits and _ . : -";
        try {
            switch (type) {
                case "boolean":
                    if (!value.equals("true") && !value.equals("false")) return "Boolean value must be true or false.";
                    break;
                case "long":
                    Long.parseLong(value);
                    break;
                case "double":
                    Double.parseDouble(value);
                    break;
                case "string":
                    break;
                default:
                    return "This flag type cannot be written.";
            }
        } catch (NumberFormatException e) {
            return "\"" + value + "\" is not a valid " + type + ".";
        }
        return null;
    }

    /** SQL for one override. Package-private so it can be unit-tested. */
    static String insertSql(String pkg, String name, String type, String value) {
        int typeCode;
        String sqlValue;
        switch (type) {
            case "boolean":
                typeCode = 2;
                sqlValue = value.equals("true") ? "1" : "0";
                break;
            case "long":
                typeCode = 1;
                sqlValue = Long.toString(Long.parseLong(value));
                break;
            case "double":
                typeCode = 3;
                sqlValue = Double.toString(Double.parseDouble(value));
                break;
            case "string":
                typeCode = 4;
                sqlValue = sqlString(value);
                break;
            default:
                throw new IllegalArgumentException("Unsupported type " + type);
        }
        String id = "(SELECT config_package_id FROM config_packages WHERE name = " + sqlString(pkg) + ")";
        // Exactly one of config_package_id / config_package_name may be set (CHECK constraint).
        return "INSERT OR REPLACE INTO flag_overrides "
                + "(config_package_id, config_package_name, account_id, active, name, value, type, source) VALUES ("
                + id + ", CASE WHEN " + id + " IS NULL THEN " + sqlString(pkg) + " END, 0, 1, "
                + sqlString(name) + ", " + sqlValue + ", " + typeCode + ", 0);";
    }

    public static String set(Context context, String pkg, String name, String type, String value) {
        return backup(context)
                + "\n== Writing override ==\n"
                + sql(context, "", insertSql(pkg, name, type, value))
                + fixLabels();
    }

    public static String remove(Context context, String pkg, String name) {
        String ids = OUR_ROWS + " AND o.name = " + sqlString(name)
                + " AND COALESCE(p.name, o.config_package_name) = " + sqlString(pkg);
        return backup(context) + "\n== Removing override ==\n" + sql(context, "", deleteSql(ids)) + fixLabels();
    }

    /** Removes every override for the Android Auto flag sets. Other apps' overrides are left alone. */
    public static String removeAll(Context context) {
        return backup(context) + "\n== Removing all Android Auto overrides ==\n"
                + sql(context, "", deleteSql(OUR_ROWS)) + fixLabels();
    }

    private static String deleteSql(String idQuery) {
        return "CREATE TEMP TABLE aat_ids AS " + idQuery + "; "
                + "DELETE FROM flag_overrides_to_commit WHERE override_id IN (SELECT override_id FROM aat_ids); "
                + "DELETE FROM experiment_states_to_overrides WHERE override_id IN (SELECT override_id FROM aat_ids); "
                + "DELETE FROM flag_overrides WHERE override_id IN (SELECT override_id FROM aat_ids); "
                + "DROP TABLE aat_ids;";
    }

    /** Table dump plus a raw copy of the database files, kept in the app's files/backups folder. */
    public static String backup(Context context) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File root = new File(context.getFilesDir(), "backups");
        File dir = new File(root, stamp);
        if (!dir.mkdirs()) return "== Backup FAILED: cannot create " + dir + " ==\n";
        try (FileOutputStream out = new FileOutputStream(new File(dir, "overrides.txt"))) {
            out.write(dump(context).getBytes("UTF-8"));
        } catch (Exception e) {
            return "== Backup FAILED: " + e + " ==\n";
        }
        String copy = Root.output("cp -p " + DB + " " + DB + "-wal " + DB + "-shm " + Root.quote(dir.getPath()) + "/ 2>&1; "
                + "ls -1d " + Root.quote(root.getPath()) + "/*/ | sort -r | tail -n +" + (KEEP_BACKUPS + 1) + " | xargs -r rm -rf");
        return "== Backup saved: " + dir.getPath() + " ==\n" + copy;
    }

    public static String dump(Context context) {
        return "== flag_overrides ==\n" + sql(context, "-header", "SELECT * FROM flag_overrides;")
                + "\n== flag_overrides_to_commit ==\n" + sql(context, "-header", "SELECT * FROM flag_overrides_to_commit;")
                + "\n== experiment_states_to_overrides ==\n" + sql(context, "-header", "SELECT * FROM experiment_states_to_overrides;");
    }

    /** Root touched the database files; make sure their SELinux labels are still Play Services'. */
    private static String fixLabels() {
        return Root.output("restorecon -R " + DB_DIR + " 2>&1");
    }

    private static String sql(Context context, String options, String statement) {
        return Root.output(Root.sqlite(context) + " " + options + " " + DB + " " + Root.quote(statement));
    }

    static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String typeName(String code) {
        switch (code) {
            case "1":
                return "long";
            case "2":
                return "boolean";
            case "3":
                return "double";
            case "4":
                return "string";
            default:
                return "type " + code;
        }
    }
}
