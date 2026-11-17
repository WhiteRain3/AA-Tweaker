package sksa.aa.tweaker;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.net.Uri;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import sksa.aa.tweaker.relay.RelayActivity;

/** Home screen: status, the restart/preview loop, and links to the tools. */
public class HomeActivity extends AppCompatActivity {

    private interface Step {
        String run();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        status = findViewById(R.id.status_text);
        output = findViewById(R.id.output_text);

        findViewById(R.id.btn_restart_preview).setOnClickListener(v -> run(() -> AndroidAuto.restartForPreview(this)));
        findViewById(R.id.btn_stop_aa).setOnClickListener(v -> run(AndroidAuto::stop));
        findViewById(R.id.btn_start_server).setOnClickListener(v -> run(() -> AndroidAuto.startHeadUnitServer(this)));
        findViewById(R.id.btn_stop_server).setOnClickListener(v -> run(() -> AndroidAuto.stopHeadUnitServer(this)));
        findViewById(R.id.btn_flags).setOnClickListener(v -> startActivity(new Intent(this, FlagsActivity.class)));
        findViewById(R.id.btn_direct).setOnClickListener(v -> startActivity(new Intent(this, DirectActivity.class)));
        findViewById(R.id.btn_open_aax).setOnClickListener(v -> {
            if (!AndroidAuto.open(this, AndroidAuto.EXPERIMENTS)) {
                Toast.makeText(this, R.string.home_aax_missing, Toast.LENGTH_LONG).show();
            }
        });
        findViewById(R.id.btn_relay).setOnClickListener(v -> startActivity(new Intent(this, RelayActivity.class)));
        findViewById(R.id.btn_diagnostics).setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        findViewById(R.id.btn_aa_settings).setOnClickListener(v -> {
            if (!AndroidAuto.open(this, AndroidAuto.PACKAGE)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + AndroidAuto.PACKAGE)));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> {
            final String text = buildStatus();
            main.post(() -> status.setText(text));
        }).start();
    }

    private String buildStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android Auto: ").append(orMissing(AndroidAuto.version(this, AndroidAuto.PACKAGE))).append('\n');
        sb.append("Play Services: ").append(orMissing(AndroidAuto.version(this, AndroidAuto.GMS))).append('\n');
        sb.append("AA Experiments: ").append(orMissing(AndroidAuto.version(this, AndroidAuto.EXPERIMENTS))).append('\n');
        String server = AndroidAuto.findHeadUnitService(this);
        sb.append("Head unit server: ").append(server == null ? "not found in this version" : "found").append('\n');
        boolean root = Root.isAvailable();
        sb.append("Root: ").append(root ? "yes" : "NO");
        if (!root) sb.append("\n\n").append(getString(R.string.home_no_root));
        return sb.toString();
    }

    private static String orMissing(String version) {
        return version == null ? "not installed" : version;
    }

    private void run(Step step) {
        output.setText(R.string.working);
        new Thread(() -> {
            final String text = step.run();
            main.post(() -> output.setText(text.trim()));
        }).start();
    }
}
