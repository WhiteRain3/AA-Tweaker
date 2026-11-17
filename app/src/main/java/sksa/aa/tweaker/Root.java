package sksa.aa.tweaker;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Runs shell commands as root and installs the SQL helper the app uses to read phenotype.db. */
public final class Root {

    private static final String TAG = "AATweaker";

    private Root() {
    }

    /** Runs one command line in a root shell and returns its output. Blocks; call off the UI thread. */
    public static StreamLogs run(String cmd) {
        StreamLogs logs = new StreamLogs();
        logs.setOutputStreamLog(cmd);
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream in = new DataOutputStream(su.getOutputStream());
            in.writeBytes(cmd + "\nexit\n");
            in.flush();

            // Drain stdout and stderr while the command runs. Waiting for exit first deadlocks
            // as soon as the output is larger than the pipe buffer (about 64 KB).
            final InputStream stderr = su.getErrorStream();
            final String[] errorText = {""};
            Thread errorReader = new Thread(() -> {
                try {
                    errorText[0] = readFully(stderr);
                } catch (IOException ignored) {
                }
            });
            errorReader.start();
            logs.setInputStreamLog(readFully(su.getInputStream()));
            errorReader.join();
            su.waitFor();
            logs.setErrorStreamLog(errorText[0]);
        } catch (IOException e) {
            logs.setErrorStreamLog("Could not start a root shell: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return logs;
    }

    /** True when a root shell can be opened. */
    public static boolean isAvailable() {
        return "1".equals(run("echo 1").getInputStreamLog());
    }

    /** Output of a command, with any error text appended on its own lines. */
    public static String output(String cmd) {
        StreamLogs logs = run(cmd);
        StringBuilder sb = new StringBuilder();
        if (!logs.getInputStreamLog().isEmpty()) sb.append(logs.getInputStreamLog()).append('\n');
        if (!logs.getErrorStreamLog().isEmpty()) sb.append("[stderr] ").append(logs.getErrorStreamLog()).append('\n');
        return sb.toString();
    }

    /** Quotes a string for use as one shell argument. */
    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Path of the "sqlite3" stand-in: a small shell wrapper that runs {@link RootSql} through
     * app_process with the platform's own SQLite. It is rewritten on every call because the APK
     * path changes whenever the app is updated.
     */
    public static String sqlite(Context context) {
        File wrapper = new File(context.getApplicationInfo().dataDir, "sqlite3");
        String script = "#!/system/bin/sh\n"
                + "export CLASSPATH='" + context.getApplicationInfo().sourceDir + "'\n"
                + "exec /system/bin/app_process /system/bin " + RootSql.class.getName() + " \"$@\"\n";
        try (FileOutputStream out = new FileOutputStream(wrapper)) {
            out.write(script.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write sqlite3 wrapper", e);
        }
        wrapper.setExecutable(true, false);
        wrapper.setReadable(true, false);
        return wrapper.getPath();
    }

    private static String readFully(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = is.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }
}
