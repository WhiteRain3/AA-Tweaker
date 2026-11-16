package sksa.aa.tweaker;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;



import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;


import static sksa.aa.tweaker.MainActivity.runSuWithCmd;

public class SplashActivity extends AppCompatActivity {


    Context context;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_splash);

        final Intent intent = new Intent(this, MainActivity.class);

        final NoRootDialog noRootDialog = new NoRootDialog();
        final StreamLogs isDeviceRooted =  runSuWithCmd("echo 1");

        copyAssets();

        SharedPreferences sharedPreferences = getSharedPreferences("MainActivity", MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("aa_speed_hack", false);
        editor.putBoolean("aa_six_tap", false);
        editor.putBoolean("aa_startup_policy", false);
        editor.putBoolean("aa_patched_apps", false);
        editor.putBoolean("aa_message_autoread", false);
        editor.putBoolean("aa_battery_outline", false);
        editor.putBoolean("force_ws", false);
        editor.putBoolean("force_no_ws", false);
        editor.putBoolean("force_portrait", false);
        editor.putBoolean("aa_hun_ms", false);
        editor.putBoolean("aa_media_hun", false);
        editor.putBoolean("multi_display", false);
        editor.putBoolean("battery_saver_warning", false);
        editor.putBoolean("kill_telemetry", false);
        editor.putBoolean("aa_inertial_scroll", false);
        editor.putBoolean("aa_bitrate_usb", false);
        editor.putBoolean("aa_bitrate_wireless", false);
        editor.putBoolean("coolwalk_daynight_tweak", false);
        editor.putBoolean("aa_activate_coolwalk", false);
        editor.putBoolean("aa_deactivate_coolwalk", false);
        editor.putBoolean("aa_activate_declinesms", false);
        editor.putBoolean("aa_activate_assistant_tips", false);
        editor.putBoolean("aa_new_seekbar", false);
        editor.putBoolean("uxprototype_tweak", false);
        editor.putBoolean("aa_material_you", false);
        editor.putBoolean("aa_vertical_bar", false);
        editor.commit();




        // With root there is nothing to wait for: go straight to the main screen.
        if (isDeviceRooted.getInputStreamLog().equals("1")) {
            startActivity(intent);
            finish();
            return;
        }

        // No root: stay here so the user can retry or read why the app cannot work.
        final Button continueButton = findViewById(R.id.proceed_button);
        continueButton.setText(R.string.proceed);
        noRootDialog.show(getSupportFragmentManager(), "NoRootDialog");

        continueButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (isDeviceRooted.getInputStreamLog().equals("1")) {
                            startActivity(intent);
                            finish();
                        } else {
                            noRootDialog.show(getSupportFragmentManager(), "NoRootDialog");
                        }
                    }
                });
    }


    /**
     * Installs the "sqlite3" command the rest of the app calls. It is a small shell wrapper that
     * runs {@link RootSql} through app_process, using the platform's own SQLite. It is rewritten
     * on every start because the APK path changes whenever the app is updated.
     */
    private void copyAssets() {
        File wrapper = new File(getApplicationInfo().dataDir, "sqlite3");
        String script = "#!/system/bin/sh\n"
                + "export CLASSPATH='" + getApplicationInfo().sourceDir + "'\n"
                + "exec /system/bin/app_process /system/bin " + RootSql.class.getName() + " \"$@\"\n";
        try (FileOutputStream out = new FileOutputStream(wrapper)) {
            out.write(script.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.e("sksa.aa.tweaker", "Failed to write sqlite3 wrapper", e);
        }
        wrapper.setExecutable(true, false);
        wrapper.setReadable(true, false);
    }
}
