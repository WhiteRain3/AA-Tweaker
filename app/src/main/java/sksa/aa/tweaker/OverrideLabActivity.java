package sksa.aa.tweaker;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Test bench for the flag storage used by current Play Services (flag_overrides tables).
 * One override at a time, always behind a confirmation, always after a backup.
 *
 * Overrides are written by Play Services itself through its FLAG_OVERRIDE broadcast, so this
 * app does not have to guess how the new tables are encoded.
 */
public class OverrideLabActivity extends AppCompatActivity {

    private static final String GEARHEAD = DiagnosticsActivity.GEARHEAD;
    private static final String DB = DiagnosticsActivity.PHENOTYPE_DB;
    private static final String[] PACKAGES = {GEARHEAD, "com.google.android.gms.car"};
    private static final String[] TYPES = {"boolean", "long", "double", "string"};
    private static final int KEEP_BACKUPS = 10;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Spinner packageSpinner;
    private Spinner typeSpinner;
    private EditText nameField;
    private EditText valueField;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.lab_title);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText(R.string.lab_intro);
        form.addView(intro);

        packageSpinner = spinner(PACKAGES);
        form.addView(label(R.string.lab_package));
        form.addView(packageSpinner);

        nameField = new EditText(this);
        nameField.setHint("SystemUi__quick_settings_enabled");
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(label(R.string.lab_flag));
        form.addView(nameField);

        typeSpinner = spinner(TYPES);
        form.addView(label(R.string.lab_type));
        form.addView(typeSpinner);

        valueField = new EditText(this);
        valueField.setHint("true");
        valueField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(label(R.string.lab_value));
        form.addView(valueField);

        form.addView(button(R.string.lab_apply, this::confirmApply));
        form.addView(button(R.string.lab_restart_aa, () -> run(this::restartAndroidAuto)));
        form.addView(button(R.string.lab_refresh, () -> run(() -> "")));
        form.addView(button(R.string.lab_remove_all, this::confirmRemoveAll));

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setPadding(0, pad, 0, 0);
        form.addView(output);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(form);
        setContentView(scroll);

        run(() -> "");
    }

    // ---- actions -------------------------------------------------------------------------

    private void confirmApply() {
        final String pkg = (String) packageSpinner.getSelectedItem();
        final String type = (String) typeSpinner.getSelectedItem();
        final String name = nameField.getText().toString().trim();
        final String value = valueField.getText().toString().trim();

        String problem = validate(name, type, value);
        if (problem != null) {
            new AlertDialog.Builder(this).setMessage(problem)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.lab_confirm_title)
                .setMessage(getString(R.string.lab_confirm_apply, pkg, name, type, value))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lab_apply, (d, w) -> run(() -> apply(pkg, name, type, value)))
                .show();
    }

    private void confirmRemoveAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.lab_confirm_title)
                .setMessage(R.string.lab_confirm_remove_all)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lab_remove_all, (d, w) -> run(this::removeAll))
                .show();
    }

    static String validate(String name, String type, String value) {
        if (!name.matches("[A-Za-z0-9_.:-]+")) return "Flag name may only contain letters, digits and _ . : -";
        if (value.isEmpty() && !type.equals("string")) return "Enter a value.";
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
                default:
                    break;
            }
        } catch (NumberFormatException e) {
            return "\"" + value + "\" is not a valid " + type + ".";
        }
        return null;
    }

    private String apply(String pkg, String name, String type, String value) {
        StringBuilder sb = new StringBuilder();
        sb.append(backup());
        // --esa splits on commas, so commas inside a value are escaped for am.
        String cmd = "am broadcast -a com.google.android.gms.phenotype.FLAG_OVERRIDE"
                + " --es package " + quote(pkg)
                + " --es user " + quote("*")
                + " --esa flags " + quote(name)
                + " --esa values " + quote(value.replace(",", "\\,"))
                + " --esa types " + quote(type)
                + " com.google.android.gms";
        sb.append("\n== Asked Play Services to set the override ==\n").append(shell(cmd));
        try {
            Thread.sleep(1500); // give Play Services a moment to write the row
        } catch (InterruptedException ignored) {
        }
        return sb.toString();
    }

    private String removeAll() {
        StringBuilder sb = new StringBuilder();
        sb.append(backup());
        sb.append("\n== Removing every override ==\n");
        sb.append(sql("", "DELETE FROM flag_overrides_to_commit; "
                + "DELETE FROM experiment_states_to_overrides; "
                + "DELETE FROM flag_overrides;"));
        return sb.toString();
    }

    private String restartAndroidAuto() {
        return "\n== Restarting Android Auto ==\n" + shell("am force-stop " + GEARHEAD) + "Stopped. It starts again on the next connection.\n";
    }

    /** Logical dump of the override tables plus a raw copy of the database files. */
    private String backup() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File root = new File(getFilesDir(), "backups");
        File dir = new File(root, stamp);
        if (!dir.mkdirs()) return "== Backup FAILED: cannot create " + dir + " ==\n";
        try (FileOutputStream out = new FileOutputStream(new File(dir, "overrides.txt"))) {
            out.write(overrideTables().getBytes("UTF-8"));
        } catch (Exception e) {
            return "== Backup FAILED: " + e + " ==\n";
        }
        String copy = shell("cp -p " + DB + " " + DB + "-wal " + DB + "-shm " + quote(dir.getPath()) + "/ 2>&1; "
                + "ls -1d " + quote(root.getPath()) + "/*/ | sort -r | tail -n +" + (KEEP_BACKUPS + 1) + " | xargs -r rm -rf");
        return "== Backup saved: " + dir.getPath() + " ==\n" + copy;
    }

    private String overrideTables() {
        StringBuilder sb = new StringBuilder();
        sb.append("== Android Auto config packages ==\n");
        sb.append(sql("-header", "SELECT config_package_id, name, version, committed_experiment_state_id FROM config_packages "
                + "WHERE name IN (\"" + PACKAGES[0] + "\", \"" + PACKAGES[1] + "\");"));
        sb.append("\n== flag_overrides ==\n");
        sb.append(sql("-header", "SELECT * FROM flag_overrides;"));
        sb.append("\n== flag_overrides_to_commit ==\n");
        sb.append(sql("-header", "SELECT * FROM flag_overrides_to_commit;"));
        sb.append("\n== experiment_states_to_overrides ==\n");
        sb.append(sql("-header", "SELECT * FROM experiment_states_to_overrides;"));
        return sb.toString();
    }

    // ---- plumbing ------------------------------------------------------------------------

    private interface Step {
        String run();
    }

    /** Runs a step off the UI thread, then shows its log followed by the current tables. */
    private void run(Step step) {
        output.setText(R.string.diagnostics_running);
        new Thread(() -> {
            final String text = step.run() + "\n" + overrideTables();
            main.post(() -> output.setText(text));
        }).start();
    }

    private String sql(String options, String statement) {
        return shell(getApplicationInfo().dataDir + "/sqlite3 " + options + " " + DB + " '" + statement + "'");
    }

    private static String shell(String cmd) {
        StreamLogs logs = MainActivity.runSuWithCmd(cmd);
        StringBuilder sb = new StringBuilder();
        if (!logs.getInputStreamLog().isEmpty()) sb.append(logs.getInputStreamLog()).append('\n');
        if (!logs.getErrorStreamLog().isEmpty()) sb.append("[stderr] ").append(logs.getErrorStreamLog()).append('\n');
        return sb.toString();
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        return s;
    }

    private TextView label(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private Button button(int text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(v -> action.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }
}
