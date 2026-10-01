package dev.lumen.glasses;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Rokid Nexus (by R08 Access Bridge's author) owns local ADB on glasses where it's set up, and
 * R08 Access Bridge defers to it. This app has no contract with Nexus's watchdog, so instead of
 * switching USB debugging or the ADB port underneath it (which can restart adbd and kill its
 * helpers), the self-arm stops and says why.
 */
final class NexusGuard {
    static final String NEXUS_PACKAGE = "com.anezium.rokidbus.glasses";
    private static final String NEXUS_SERVICE = NEXUS_PACKAGE + ".RokidBusAccessibilityService";

    private NexusGuard() {
    }

    static boolean isPresent(Context context) {
        return isConfigured(context) || isInstalled(context);
    }

    private static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(NEXUS_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean isConfigured(Context context) {
        String services = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) {
            return false;
        }
        for (String service : services.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(service);
            if (component != null && NEXUS_PACKAGE.equals(component.getPackageName())
                    && NEXUS_SERVICE.equals(component.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
