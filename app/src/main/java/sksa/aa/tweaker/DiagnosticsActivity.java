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

/**
 * Read-only report of everything the tweaks depend on: app versions, root, SELinux, and the
 * state of the Play Services phenotype database. Nothing on this screen writes anything.
 */
public class DiagnosticsActivity extends AppCompatActivity {

    static final String GEARHEAD = "com.google.android.projection.gearhead";
    static final String GMS = "com.google.android.gms";
    static final String PHENOTYPE_DB = "/data/data/com.google.android.gms/databases/phenotype.db";

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

        section(sb, "Tables");
        sql(sb, sqlite, "SELECT name FROM sqlite_master WHERE type=\"table\" ORDER BY name;");

        section(sb, "FlagOverrides definition");
        sql(sb, sqlite, "SELECT sql FROM sqlite_master WHERE name=\"FlagOverrides\";");

        section(sb, "Triggers (tweaks installed by this app or upstream)");
        sql(sb, sqlite, "SELECT name, tbl_name FROM sqlite_master WHERE type=\"trigger\";");

        section(sb, "FlagOverrides rows per package");
        sql(sb, sqlite, "SELECT packageName, COUNT(*) FROM FlagOverrides GROUP BY packageName;");

        section(sb, "Android Auto overrides (name | bool | int | float | string)");
        sql(sb, sqlite, "SELECT name, boolVal, intVal, floatVal, stringVal FROM FlagOverrides "
                + "WHERE packageName IN (\"" + GEARHEAD + "\", \"com.google.android.gms.car\") "
                + "ORDER BY name LIMIT 300;");

        section(sb, "Server-delivered flags stored in phenotype.db (Flags table)");
        sql(sb, sqlite, "SELECT packageName, COUNT(*) FROM Flags WHERE packageName LIKE \"%gearhead%\" "
                + "OR packageName LIKE \"%gms.car%\" GROUP BY packageName;");

        section(sb, "Android Auto's own phenotype files");
        shell(sb, "ls -laR /data/data/" + GEARHEAD + "/files/phenotype 2>&1 | head -n 40");

        sb.append("\n-- end of report. Nothing was modified. --\n");
        return sb.toString();
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
