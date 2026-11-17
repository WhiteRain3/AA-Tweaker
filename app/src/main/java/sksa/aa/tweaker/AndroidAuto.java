package sksa.aa.tweaker;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import java.util.Locale;

/** Actions on the installed Android Auto app: version, restart, and the developer head unit server. */
public final class AndroidAuto {

    public static final String PACKAGE = "com.google.android.projection.gearhead";
    public static final String GMS = "com.google.android.gms";
    public static final String EXPERIMENTS = "io.github.highwindbr.aaxp";

    private AndroidAuto() {
    }

    public static String version(Context context, String pkg) {
        try {
            return context.getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Force-stops Android Auto so it re-reads its flags on the next start. */
    public static String stop() {
        return Root.output("am force-stop " + PACKAGE) + "Android Auto stopped.\n";
    }

    /**
     * Finds the service behind "Start head unit server" in Android Auto's developer menu.
     * The class name is looked up on the installed version instead of being hard-coded,
     * because Google renames internals between releases.
     */
    public static String findHeadUnitService(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE,
                    PackageManager.GET_SERVICES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (info.services == null) return null;
            String fallback = null;
            for (ServiceInfo s : info.services) {
                String name = s.name.toLowerCase(Locale.ROOT);
                if (!name.contains("headunit")) continue;
                if (name.contains("developer")) return s.name;
                if (fallback == null) fallback = s.name;
            }
            return fallback;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Starts the developer head unit server (what DHU connects to). Needs root. */
    public static String startHeadUnitServer(Context context) {
        String service = findHeadUnitService(context);
        if (service == null) {
            return "Could not find the head unit server in this Android Auto version.\n"
                    + "Start it by hand: Android Auto settings > three-dot menu > Start head unit server.\n";
        }
        String component = Root.quote(PACKAGE + "/" + service);
        String out = Root.output("am start-foreground-service -n " + component
                + " || am startservice -n " + component);
        return "Head unit server: " + service + "\n" + out;
    }

    public static String stopHeadUnitServer(Context context) {
        String service = findHeadUnitService(context);
        if (service == null) return "Head unit server not found.\n";
        return Root.output("am stopservice -n " + Root.quote(PACKAGE + "/" + service)) + "Head unit server stop requested.\n";
    }

    /** The DHU loop in one step: restart Android Auto, then bring the head unit server back up. */
    public static String restartForPreview(Context context) {
        StringBuilder sb = new StringBuilder(stop());
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sb.append(startHeadUnitServer(context));
        return sb.toString();
    }

    /** Opens an installed app, or returns false when it is missing. */
    public static boolean open(Context context, String pkg) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }
}
