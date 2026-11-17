package sksa.aa.tweaker;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sksa.aa.tweaker.FlagRepository.Flag;
import sksa.aa.tweaker.FlagRepository.Note;

/**
 * Searchable reference of Android Auto flags: what each one does, its risk, and its values
 * (built-in default, what Google set, what you overrode). Read-only: changes are made in
 * AA Experiments. You can record what each flag did on your own car.
 */
public class FlagsActivity extends AppCompatActivity {

    private static final String[] STATUS_KEYS = {"", "works", "no_effect", "broken"};
    private static final int FILTER_ALL = 0, FILTER_DESCRIBED = 1, FILTER_GOOGLE = 2, FILTER_MINE = 3, FILTER_NOTES = 4;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Flag> shown = new ArrayList<>();
    private List<Flag> all = new ArrayList<>();
    private FlagRepository repository;
    private FlagAdapter adapter;
    private EditText search;
    private TextView count;
    private int filter = FILTER_DESCRIBED;
    private String category; // null = any
    private String sourceStatus = "";
    private String aaVersion = "";

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importFrom);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_flags);
        repository = new FlagRepository(this);
        String v = AndroidAuto.version(this, AndroidAuto.PACKAGE);
        aaVersion = v == null ? "" : v;

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(x -> finish());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.import_export) {
                picker.launch(new String[]{"application/json", "text/*", "application/octet-stream"});
            } else if (item.getItemId() == R.id.copy_notes) {
                copy("AA Tweaker notes", repository.exportNotes());
            }
            return true;
        });

        search = findViewById(R.id.search);
        count = findViewById(R.id.count);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FlagAdapter();
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int c, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int c) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        String[] filters = getResources().getStringArray(R.array.flag_filters);
        ChipGroup filterGroup = findViewById(R.id.filter_chips);
        for (int i = 0; i < filters.length; i++) {
            final int index = i;
            addChip(filterGroup, filters[i], i == filter, () -> {
                filter = index;
                applyFilter();
            });
        }
        ChipGroup categoryGroup = findViewById(R.id.category_chips);
        addChip(categoryGroup, "Any category", true, () -> {
            category = null;
            applyFilter();
        });
        for (final String c : FlagRepository.CATEGORIES) {
            addChip(categoryGroup, capitalize(c), false, () -> {
                category = c;
                applyFilter();
            });
        }

        reload();
    }

    private void addChip(ChipGroup group, String text, boolean checked, Runnable onSelected) {
        Chip chip = (Chip) LayoutInflater.from(this).inflate(R.layout.chip_filter, group, false);
        chip.setId(View.generateViewId());
        chip.setText(text);
        chip.setCheckable(true);
        group.addView(chip);
        chip.setChecked(checked);
        chip.setOnCheckedChangeListener((button, isChecked) -> {
            if (isChecked) onSelected.run();
        });
    }

    /** Loads the catalog and any imported export first, then Google's values (slower, needs root). */
    private void reload() {
        count.setText(R.string.working);
        new Thread(() -> {
            final Map<String, Flag> flags = new LinkedHashMap<>(repository.load());
            main.post(() -> show(flags, getString(R.string.working)));
            final String status = repository.mergeServerValues(flags);
            main.post(() -> show(flags, status));
        }).start();
    }

    private void show(Map<String, Flag> flags, String status) {
        all = FlagRepository.sorted(flags);
        sourceStatus = status;
        applyFilter();
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Flag f : all) {
            if (category != null && !category.equals(f.category)) continue;
            if (filter == FILTER_DESCRIBED && !f.described()) continue;
            if (filter == FILTER_GOOGLE && f.serverValue == null) continue;
            if (filter == FILTER_MINE && f.override == null) continue;
            if (filter == FILTER_NOTES && !repository.hasNote(f.name)) continue;
            if (!q.isEmpty() && !matches(f, q)) continue;
            shown.add(f);
        }
        adapter.notifyDataSetChanged();
        String hint = repository.hasExport() ? sourceStatus : getString(R.string.flags_no_export);
        count.setText(getString(R.string.flags_count, shown.size(), all.size(), hint));
    }

    private static boolean matches(Flag f, String q) {
        return f.name.toLowerCase(Locale.ROOT).contains(q)
                || (f.title != null && f.title.toLowerCase(Locale.ROOT).contains(q))
                || (f.description != null && f.description.toLowerCase(Locale.ROOT).contains(q));
    }

    private void importFrom(Uri uri) {
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            int n = repository.importExport(in);
            Toast.makeText(this, getString(R.string.flags_imported, n), Toast.LENGTH_LONG).show();
            filter = FILTER_ALL;
            reload();
        } catch (Exception e) {
            Toast.makeText(this, R.string.flags_import_failed, Toast.LENGTH_LONG).show();
        }
    }

    // ---- text helpers --------------------------------------------------------------------

    static String riskLabel(String risk) {
        if (risk == null) return "Effect unknown";
        switch (risk) {
            case "safe":
                return "Safe";
            case "may_break":
                return "May break";
            case "safety":
                return "Driving safety";
            default:
                return "Experimental";
        }
    }

    private static String values(Flag f) {
        List<String> parts = new ArrayList<>();
        if (f.defaultValue != null) parts.add("Default " + shortValue(f.defaultValue));
        if (f.serverValue != null) parts.add("Google " + shortValue(f.serverValue));
        if (f.override != null) parts.add("You " + shortValue(f.override));
        return parts.isEmpty() ? "" : android.text.TextUtils.join("  ·  ", parts);
    }

    private static String shortValue(String v) {
        if (v.isEmpty()) return "(empty)";
        return v.length() > 40 ? v.substring(0, 40) + "…" : v;
    }

    private String badges(Flag f) {
        List<String> parts = new ArrayList<>();
        if (f.described()) parts.add(riskLabel(f.risk));
        if (f.verified != null) parts.add("Checked on this car");
        Note note = repository.note(f.name);
        if (!note.status.isEmpty()) {
            String label = statusLabel(note.status);
            if (!note.aaVersion.isEmpty() && !note.aaVersion.equals(aaVersion)) {
                label += " (on " + note.aaVersion + ", not re-checked)";
            }
            parts.add("Mine: " + label);
        }
        if (!f.editable) parts.add("Read-only in AA Experiments");
        return android.text.TextUtils.join("  ·  ", parts);
    }

    private String statusLabel(String key) {
        String[] labels = getResources().getStringArray(R.array.flag_statuses);
        for (int i = 0; i < STATUS_KEYS.length; i++) {
            if (STATUS_KEYS[i].equals(key)) return labels[i];
        }
        return key;
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    // ---- detail dialog -------------------------------------------------------------------

    private void showDetail(final Flag f) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_flag, null);
        StringBuilder sb = new StringBuilder();
        sb.append(f.name).append("\n\n");
        sb.append(f.described() ? f.description : "No description yet. What this flag does is not known here.").append("\n\n");
        if (f.described()) sb.append("Risk: ").append(riskLabel(f.risk)).append('\n');
        if (f.type != null && !f.type.isEmpty()) sb.append("Type: ").append(f.type).append('\n');
        if (f.defaultValue != null) sb.append("Built-in default: ").append(shortValue(f.defaultValue)).append('\n');
        if (f.serverValue != null) sb.append("Set by Google: ").append(shortValue(f.serverValue)).append('\n');
        if (f.override != null) sb.append("Your override: ").append(shortValue(f.override)).append('\n');
        if (f.suggest != null) sb.append("\nTry: ").append(f.suggest).append('\n');
        if (f.verified != null) sb.append("\nChecked: ").append(f.verified).append('\n');
        if (f.serverValue != null && f.override == null) {
            sb.append("\nGoogle has set this one. To change it, pick the value explicitly in AA Experiments; \"Default\" keeps Google's value.\n");
        }
        ((TextView) view.findViewById(R.id.detail_text)).setText(sb.toString().trim());

        final Note note = repository.note(f.name);
        final Spinner statusSpinner = view.findViewById(R.id.detail_status);
        statusSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.flag_statuses)));
        for (int i = 0; i < STATUS_KEYS.length; i++) {
            if (STATUS_KEYS[i].equals(note.status)) statusSpinner.setSelection(i);
        }
        final EditText noteText = view.findViewById(R.id.detail_note);
        noteText.setText(note.text);

        new MaterialAlertDialogBuilder(this)
                .setTitle(f.title != null ? f.title : f.name)
                .setView(view)
                .setPositiveButton(R.string.flags_save, (d, w) -> {
                    Note updated = new Note();
                    updated.status = STATUS_KEYS[statusSpinner.getSelectedItemPosition()];
                    updated.text = noteText.getText().toString().trim();
                    updated.aaVersion = aaVersion;
                    repository.saveNote(f.name, updated);
                    applyFilter();
                })
                .setNeutralButton(R.string.flags_copy_name, (d, w) -> {
                    copy("flag", f.name);
                    Toast.makeText(this, R.string.flags_name_copied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.flags_open_aax, (d, w) -> {
                    copy("flag", f.name);
                    if (!AndroidAuto.open(this, AndroidAuto.EXPERIMENTS)) {
                        Toast.makeText(this, R.string.home_aax_missing, Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    // ---- list ----------------------------------------------------------------------------

    private final class FlagAdapter extends RecyclerView.Adapter<FlagHolder> {
        @NonNull
        @Override
        public FlagHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new FlagHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_flag, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull FlagHolder h, int position) {
            final Flag f = shown.get(position);
            h.title.setText(f.title != null ? f.title : f.name.replace("__", " · "));
            h.name.setText(f.name);
            h.description.setText(f.described() ? f.description : "");
            h.description.setVisibility(f.described() ? View.VISIBLE : View.GONE);
            String values = values(f);
            h.values.setText(values);
            h.values.setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
            String badges = badges(f);
            h.badges.setText(badges);
            h.badges.setVisibility(badges.isEmpty() ? View.GONE : View.VISIBLE);
            h.itemView.setOnClickListener(v -> showDetail(f));
            // Long-press: open the experimental direct-write screen for this flag.
            h.itemView.setOnLongClickListener(v -> {
                startActivity(new android.content.Intent(FlagsActivity.this, DirectActivity.class)
                        .putExtra(DirectActivity.EXTRA_NAME, f.name));
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }

    private static final class FlagHolder extends RecyclerView.ViewHolder {
        final TextView title, name, description, values, badges;

        FlagHolder(View v) {
            super(v);
            title = v.findViewById(R.id.title);
            name = v.findViewById(R.id.name);
            description = v.findViewById(R.id.description);
            values = v.findViewById(R.id.values);
            badges = v.findViewById(R.id.badges);
        }
    }
}
