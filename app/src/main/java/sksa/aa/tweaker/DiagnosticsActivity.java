package sksa.aa.tweaker;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Arrays;
import java.util.List;

/**
 * Read-only report of everything the tweaks depend on: app versions, root, SELinux, and the
 * state of the Play Services phenotype database. Nothing on this screen writes anything.
 */
public class DiagnosticsActivity extends AppCompatActivity {

    static final String GEARHEAD = "com.google.android.projection.gearhead";
    static final String GMS = "com.google.android.gms";
    static final String PHENOTYPE_DB = "/data/data/com.google.android.gms/databases/phenotype.db";

    /** Tables whose rows are never printed: they can hold account names or tokens. */
    private static final List<String> PRIVATE_TABLES =
            Arrays.asList("accounts", "dogfood_token", "cross_logged_tokens", "last_fetch");
    private static final int FIRST_ROWS = 3;
    private static final int MATCHING_ROWS = 25;
    private static final int MAX_CELL = 400;

    private TextView report;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.diagnostics_title);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        Button copy = new Button(this);
        copy.setText(R.string.diagnostics_copy);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.setMargins(pad, pad / 2, pad, 0);
        root.addView(copy, lp);

        report = new TextView(this);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextSize(12);
        report.setTextIsSelectable(true);
        report.setPadding(pad, pad, pad, pad);
        report.setText(R.string.diagnostics_running);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("AA Tweaker diagnostics", report.getText()));
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
        });

        new Thread(() -> {
            final String text = buildReport();
            new Handler(Looper.getMainLooper()).post(() -> report.setText(text));
        }).start();
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        String sqlite = getApplicationInfo().dataDir + "/sqlite3";

        section(sb, "Versions");
        sb.append("Android Auto : ").append(version(GEARHEAD)).append('\n');
        sb.append("Play Services: ").append(version(GMS)).append('\n');
        sb.append("AA Tweaker   : ").append(BuildConfig.VERSION_NAME).append('\n');
        sb.append("Android      : ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device       : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("ABIs         : ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append('\n');

        section(sb, "Root and SELinux");
        shell(sb, "id");
        shell(sb, "getenforce");

        section(sb, "SQL runner self-test (expect a SQLite version number)");
        sql(sb, sqlite, "SELECT sqlite_version();");
        sql(sb, sqlite, "PRAGMA journal_mode;");

        section(sb, "phenotype.db files");
        shell(sb, "ls -la /data/data/com.google.android.gms/databases/ | grep -i phenotype");

        section(sb, "Schema (tables, triggers, indexes)");
        sql(sb, sqlite, "SELECT type, name, sql FROM sqlite_master WHERE type IN (\"table\", \"trigger\", \"index\") ORDER BY type, name;");

        String tables = MainActivity.runSuWithCmd(sqlite + " " + PHENOTYPE_DB
                + " 'SELECT name FROM sqlite_master WHERE type=\"table\" ORDER BY name;'").getInputStreamLog();
        for (String table : tables.split("\n")) {
            table = table.trim();
            if (!table.matches("[A-Za-z0-9_]+")) continue;
            section(sb, "Table " + table);
            sql(sb, sqlite, "SELECT COUNT(*) FROM " + table + ";");
            if (PRIVATE_TABLES.contains(table)) {
                sb.append("(rows not shown: may contain account names or tokens)\n");
                continue;
            }
            sampleRows(sb, sqlite, table);
        }

        section(sb, "Phenotype files on disk mentioning Android Auto");
        shell(sb, "find /data/data/" + GEARHEAD + " /data/user_de/0/" + GEARHEAD
                + " -maxdepth 5 \\( -iname \"*phenotype*\" -o -iname \"*.pb\" \\) 2>/dev/null | head -n 40");
        shell(sb, "ls -la /data/data/" + GMS + "/files/phenotype 2>&1 | head -n 20");
        shell(sb, "find /data/data/" + GMS + "/files -maxdepth 5 \\( -iname \"*gearhead*\" -o -iname \"*gms.car*\" \\) 2>/dev/null | head -n 40");

        sb.append("\n-- end of report. Nothing was modified. --\n");
        return sb.toString();
    }

    /** Column header, the first rows, and any row that mentions Android Auto. Long cells are cut. */
    private static void sampleRows(StringBuilder sb, String sqlite, String table) {
        StreamLogs logs = MainActivity.runSuWithCmd(sqlite + " -header " + PHENOTYPE_DB
                + " 'SELECT * FROM " + table + " LIMIT 2000;'");
        if (!logs.getErrorStreamLog().isEmpty()) {
            sb.append("[stderr] ").append(logs.getErrorStreamLog()).append('\n');
        }
        String[] lines = logs.getInputStreamLog().split("\n");
        int shown = 0;
        int matches = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            boolean relevant = line.contains("gearhead") || line.contains("gms.car");
            if (i <= FIRST_ROWS || (relevant && matches < MATCHING_ROWS)) {
                if (relevant && i > FIRST_ROWS) matches++;
                sb.append(line.length() > MAX_CELL ? line.substring(0, MAX_CELL) + "…" : line).append('\n');
                shown++;
            }
        }
        if (shown < lines.length) {
            sb.append("(").append(lines.length - shown).append(" more rows not shown)\n");
        }
    }

    private String version(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "not installed";
        }
    }

    private static void section(StringBuilder sb, String title) {
        sb.append("\n== ").append(title).append(" ==\n");
    }

    private static void sql(StringBuilder sb, String sqlite, String statement) {
        shell(sb, sqlite + " " + PHENOTYPE_DB + " '" + statement + "'");
    }

    private static void shell(StringBuilder sb, String cmd) {
        StreamLogs logs = MainActivity.runSuWithCmd(cmd);
        String out = logs.getInputStreamLog();
        String err = logs.getErrorStreamLog();
        if (!out.isEmpty()) sb.append(out).append('\n');
        if (!err.isEmpty()) sb.append("[stderr] ").append(err).append('\n');
        if (out.isEmpty() && err.isEmpty()) sb.append("(no output)\n");
    }
}
