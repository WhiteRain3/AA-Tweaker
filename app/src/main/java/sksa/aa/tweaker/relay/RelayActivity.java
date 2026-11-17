package sksa.aa.tweaker.relay;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;

import sksa.aa.tweaker.R;

/** Setup screen for the message relay. */
public class RelayActivity extends AppCompatActivity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.relay_title);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        final SharedPreferences prefs = getSharedPreferences(RelayService.PREFS, MODE_PRIVATE);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText(R.string.relay_intro);
        form.addView(intro);

        status = new TextView(this);
        status.setPadding(0, pad, 0, pad);
        form.addView(status);

        CheckBox enabled = new CheckBox(this);
        enabled.setText(R.string.relay_enabled);
        enabled.setChecked(prefs.getBoolean(RelayService.PREF_ENABLED, true));
        enabled.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(RelayService.PREF_ENABLED, on).apply());
        form.addView(enabled);

        CheckBox onlyInCar = new CheckBox(this);
        onlyInCar.setText(R.string.relay_only_in_car);
        onlyInCar.setChecked(prefs.getBoolean(RelayService.PREF_ONLY_IN_CAR, true));
        onlyInCar.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(RelayService.PREF_ONLY_IN_CAR, on).apply());
        form.addView(onlyInCar);

        form.addView(button(R.string.relay_grant_access,
                () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        form.addView(button(R.string.relay_grant_post, this::requestPostPermission));
        form.addView(button(R.string.relay_test, this::postTest));

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(form);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean access = NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
        boolean post = NotificationManagerCompat.from(this).areNotificationsEnabled();
        status.setText(getString(R.string.relay_status,
                getString(access ? R.string.relay_yes : R.string.relay_no),
                getString(post ? R.string.relay_yes : R.string.relay_no)));
    }

    private void requestPostPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    /** Posts a fake message so the Android Auto side can be checked without a real sender. */
    private void postTest() {
        RelayService.ensureChannel(this);
        Person me = new Person.Builder().setName(getString(R.string.relay_me)).build();
        Person sender = new Person.Builder().setName(getString(R.string.relay_test_sender)).build();
        NotificationCompat.MessagingStyle style = new NotificationCompat.MessagingStyle(me)
                .addMessage(getString(R.string.relay_test_text), System.currentTimeMillis(), sender);
        String key = "test";
        try {
            NotificationManagerCompat.from(this).notify(RelayService.NOTIFICATION_TAG, RelayService.idFor(key),
                    RelayService.decorate(this, style, key, System.currentTimeMillis(), true));
        } catch (SecurityException e) {
            requestPostPermission();
        }
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
