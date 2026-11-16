package sksa.aa.tweaker;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only list of the flags Play Services currently holds for Android Auto, decoded from
 * phenotype.db. These are the values the server delivered for this exact Android Auto version;
 * flags that only exist as defaults inside the Android Auto APK do not appear here.
 */
public class FlagBrowserActivity extends AppCompatActivity {

    static final String[] PACKAGES = {DiagnosticsActivity.GEARHEAD, "com.google.android.gms.car"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> all = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView status;
    private EditText search;
    private Spinner packageSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.browser_title);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(pad, pad / 2, pad, 0);

        packageSpinner = new Spinner(this);
        packageSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PACKAGES));
        root.addView(packageSpinner);

        search = new EditText(this);
        search.setHint(R.string.browser_search);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        root.addView(search);

        status = new TextView(this);
        root.addView(status);

        Button copy = new Button(this);
        copy.setText(R.string.browser_copy);
        root.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, shown);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        packageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                load(PACKAGES[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        copy.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (String line : shown) sb.append(line).append('\n');
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Android Auto flags", sb));
            Toast.makeText(this, getString(R.string.browser_copied, shown.size()), Toast.LENGTH_SHORT).show();
        });
        // Tapping a flag opens the override lab with it filled in.
        list.setOnItemClickListener((parent, view, position, id) -> {
            String[] parts = shown.get(position).split("  =  ", 2);
            String[] head = parts[0].split(" ", 2);
            if (head.length < 2 || parts.length < 2) return;
            Intent intent = new Intent(this, OverrideLabActivity.class);
            intent.putExtra(OverrideLabActivity.EXTRA_PACKAGE, (String) packageSpinner.getSelectedItem());
            intent.putExtra(OverrideLabActivity.EXTRA_NAME, head[1]);
            intent.putExtra(OverrideLabActivity.EXTRA_TYPE, head[0]);
            intent.putExtra(OverrideLabActivity.EXTRA_VALUE, parts[1]);
            startActivity(intent);
        });
    }

    private void load(final String pkg) {
        status.setText(R.string.diagnostics_running);
        new Thread(() -> {
            StreamLogs logs = MainActivity.runSuWithCmd(getApplicationInfo().dataDir + "/sqlite3 -flags "
                    + DiagnosticsActivity.PHENOTYPE_DB + " " + OverrideLabActivity.quote(pkg));
            final List<String> lines = new ArrayList<>();
            for (String line : logs.getInputStreamLog().split("\n")) {
                String[] f = line.split("\\|", 3);
                if (f.length < 3) continue;
                lines.add(typeName(f[0]) + " " + f[1] + "  =  " + f[2]);
            }
            final String summary = logs.getErrorStreamLog();
            main.post(() -> {
                all.clear();
                all.addAll(lines);
                filter();
                if (lines.isEmpty()) status.setText(getString(R.string.browser_empty) + "\n" + summary);
            });
        }).start();
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (String line : all) {
            if (q.isEmpty() || line.toLowerCase(Locale.ROOT).contains(q)) shown.add(line);
        }
        adapter.notifyDataSetChanged();
        status.setText(getString(R.string.browser_count, shown.size(), all.size()));
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
}
