package sksa.aa.tweaker;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Experimental direct write of one flag override into Play Services' database, as an
 * alternative to AA Experiments. Every change is confirmed and backed up first.
 */
public class DirectActivity extends AppCompatActivity {

    static final String EXTRA_NAME = "name";

    private interface Step {
        String run();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Map<String, FlagRepository.Flag> known;
    private List<DirectOverrides.Row> current = new ArrayList<>();
    private Spinner packageSpinner;
    private Spinner typeSpinner;
    private AutoCompleteTextView nameField;
    private EditText valueField;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.direct_title);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        known = new FlagRepository(this).load();

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText(R.string.direct_intro);
        form.addView(intro);

        form.addView(label(R.string.direct_package));
        packageSpinner = spinner(DirectOverrides.PACKAGES);
        form.addView(packageSpinner);

        form.addView(label(R.string.direct_flag));
        nameField = new AutoCompleteTextView(this);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameField.setThreshold(2);
        nameField.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line,
                new ArrayList<>(known.keySet())));
        form.addView(nameField);

        form.addView(label(R.string.direct_type));
        typeSpinner = spinner(DirectOverrides.TYPES);
        form.addView(typeSpinner);

        form.addView(label(R.string.direct_value));
        valueField = new EditText(this);
        valueField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(valueField);

        form.addView(button(R.string.direct_write, this::confirmWrite));
        form.addView(button(R.string.direct_remove, this::confirmRemove));
        form.addView(button(R.string.direct_remove_all, this::confirmRemoveAll));
        form.addView(button(R.string.home_stop_aa, () -> run(AndroidAuto::stop)));

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

        // Picking a known flag fills in its type.
        nameField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                FlagRepository.Flag f = known.get(s.toString().trim());
                if (f == null || f.type == null) return;
                for (int i = 0; i < DirectOverrides.TYPES.length; i++) {
                    if (DirectOverrides.TYPES[i].equals(f.type)) typeSpinner.setSelection(i);
                }
            }
        });
        String preset = getIntent().getStringExtra(EXTRA_NAME);
        if (preset != null) nameField.setText(preset);

        run(() -> DirectOverrides.supported(this) ? "" : getString(R.string.direct_unsupported));
    }

    private void confirmWrite() {
        final String pkg = (String) packageSpinner.getSelectedItem();
        final String type = (String) typeSpinner.getSelectedItem();
        final String name = nameField.getText().toString().trim();
        final String value = valueField.getText().toString().trim();
        String problem = DirectOverrides.validate(name, type, value);
        if (problem != null) {
            new MaterialAlertDialogBuilder(this).setMessage(problem).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String before = getString(R.string.direct_not_set);
        for (DirectOverrides.Row r : current) {
            if (r.name.equals(name) && r.pkg.equals(pkg)) before = r.value;
        }
        String warning = known.containsKey(name) ? "" : "\n\n" + getString(R.string.direct_unknown_flag);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.direct_confirm)
                .setMessage(getString(R.string.direct_confirm_write, name, before, value, type, pkg) + warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.direct_write, (d, w) -> run(() -> DirectOverrides.set(this, pkg, name, type, value)))
                .show();
    }

    private void confirmRemove() {
        final String pkg = (String) packageSpinner.getSelectedItem();
        final String name = nameField.getText().toString().trim();
        if (name.isEmpty()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.direct_confirm)
                .setMessage(getString(R.string.direct_confirm_remove, name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.direct_remove, (d, w) -> run(() -> DirectOverrides.remove(this, pkg, name)))
                .show();
    }

    private void confirmRemoveAll() {
        StringBuilder list = new StringBuilder();
        for (DirectOverrides.Row r : current) list.append("\n• ").append(r.name).append(" = ").append(r.value);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.direct_confirm)
                .setMessage(getString(R.string.direct_confirm_remove_all) + (list.length() == 0 ? "\n(none)" : list.toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.direct_remove_all, (d, w) -> run(() -> DirectOverrides.removeAll(this)))
                .show();
    }

    /** Runs a step off the UI thread, then shows its log followed by the current overrides. */
    private void run(Step step) {
        output.setText(R.string.working);
        new Thread(() -> {
            final String log = step.run();
            final List<DirectOverrides.Row> rows = DirectOverrides.list(this);
            final StringBuilder sb = new StringBuilder(log.trim());
            sb.append("\n\n== Android Auto overrides in Play Services ==\n");
            if (rows.isEmpty()) sb.append("(none)\n");
            for (DirectOverrides.Row r : rows) {
                sb.append(r.name).append(" = ").append(r.value).append("  (").append(r.type).append(")\n");
            }
            main.post(() -> {
                current = rows;
                output.setText(sb.toString().trim());
            });
        }).start();
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
