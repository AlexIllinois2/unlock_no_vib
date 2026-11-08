package dev.n8p.unlockvibfix;

import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Disables the unlock success/failure haptics on the lock screen.
 *
 * Three independent paths produce them:
 *  - SystemUI: MSDL haptics played by BouncerHapticPlayer / PromptViewModel with the
 *    UNLOCK and FAILURE tokens (HARDWARE_FEEDBACK usage, unaffected by ringer mode).
 *    MSDL only exists on Android 16+.
 *  - SystemUI: the DeviceEntry haptics path (View.performHapticFeedback with the private
 *    constants 10004/10005), gated by Settings.System.fp_success_vibrate/fp_error_vibrate.
 *    Available since Android 15.
 *  - system_server: AcquisitionClient.vibrateSuccess() plays a CLICK when a biometric
 *    (face/fingerprint) authentication succeeds. Since Android 12.
 *    Android 12/13 also play a CLICK on failure via vibrateError().
 *
 * Every hook is installed independently: a missing class/method is logged as skipped and
 * never prevents the remaining hooks from being applied. Hooks are installed at the
 * source, so the result does not depend on the ringer mode.
 */
public class Module implements IXposedHookLoadPackage {

    private static final String TAG = "UnlockVibFix: ";
    private static final String SYSTEMUI = "com.android.systemui";
    /** system_server runs in the "android" package, but its injection scope is "system". */
    private static final String SYSTEM = "android";

    /** Private HapticFeedbackConstants used only by KeyguardRootViewBinder (success). */
    private static final int HAPTIC_DEVICE_ENTRY_SUCCESS = 10004;
    /** Private HapticFeedbackConstants used only by KeyguardRootViewBinder (error). */
    private static final int HAPTIC_DEVICE_ENTRY_ERROR = 10005;

    private static volatile boolean sLoggedBiometricSuccess;
    private static volatile boolean sLoggedBiometricError;

    /** Per-process hook counters, reported once at the end of the injection. */
    private static final class Stats {
        int installed;
        int skipped;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SYSTEMUI.equals(lpparam.packageName)) {
            Stats stats = new Stats();
            // Independent try/catch: on Android 14 and older the MSDL class is absent and
            // must not prevent the View haptic hook from being installed.
            try {
                hookMsdl(lpparam, stats);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            try {
                hookDeviceEntryHaptics(lpparam, stats);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            logSummary(lpparam, stats);
            return;
        }
        if (SYSTEM.equals(lpparam.packageName)) {
            Stats stats = new Stats();
            try {
                hookBiometricVibration(lpparam, stats);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            logSummary(lpparam, stats);
        }
    }

    /**
     * Suppresses the MSDL UNLOCK/FAILURE tokens (bouncer authentication feedback and the
     * biometric prompt error feedback). Other tokens (key presses, drag indicators...) are kept.
     */
    private void hookMsdl(XC_LoadPackage.LoadPackageParam lpparam, Stats stats) {
        Class<?> player = findClassIfExists(
                "com.google.android.msdl.domain.MSDLPlayerImpl", lpparam.classLoader, "MSDL", stats);
        if (player == null) {
            return;
        }
        hookAllMethods(player, "playToken", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object token = param.args.length > 0 ? param.args[0] : null;
                String name = token == null ? "null" : token.toString();
                if ("UNLOCK".equals(name) || "FAILURE".equals(name)) {
                    param.setResult(null);
                    XposedBridge.log(TAG + "suppressed MSDL " + name);
                }
            }
        }, "MSDL", stats);
    }

    /**
     * Suppresses the DeviceEntry success/error haptics. fp_success_vibrate/fp_error_vibrate are
     * removed (their in-code default is 1 = enabled), so this path would otherwise vibrate again.
     */
    private void hookDeviceEntryHaptics(XC_LoadPackage.LoadPackageParam lpparam, Stats stats) {
        Class<?> view = findClassIfExists("android.view.View", lpparam.classLoader,
                "DeviceEntry", stats);
        if (view == null) {
            return;
        }
        hookAllMethods(view, "performHapticFeedback", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int constant = (Integer) param.args[0];
                if (constant == HAPTIC_DEVICE_ENTRY_SUCCESS || constant == HAPTIC_DEVICE_ENTRY_ERROR) {
                    param.setResult(Boolean.FALSE);
                    XposedBridge.log(TAG + "suppressed device entry haptic " + constant);
                }
            }
        }, "DeviceEntry", stats);
    }

    /**
     * Suppresses the biometric CLICKs played by system_server right after an authentication
     * (reason e.g. "FaceAuthenticationClient::success"). vibrateError() only exists on
     * Android 12/13 and is skipped (with a log line) on 14+.
     */
    private void hookBiometricVibration(XC_LoadPackage.LoadPackageParam lpparam, Stats stats) {
        Class<?> client = findClassIfExists(
                "com.android.server.biometrics.sensors.AcquisitionClient", lpparam.classLoader,
                "biometric", stats);
        if (client == null) {
            return;
        }
        hookAllMethods(client, "vibrateSuccess", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
                if (!sLoggedBiometricSuccess) {
                    sLoggedBiometricSuccess = true;
                    XposedBridge.log(TAG + "suppressed biometric success vibration");
                }
            }
        }, "biometric success", stats);
        hookAllMethods(client, "vibrateError", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(null);
                if (!sLoggedBiometricError) {
                    sLoggedBiometricError = true;
                    XposedBridge.log(TAG + "suppressed biometric error vibration");
                }
            }
        }, "biometric error", stats);
    }

    /** Looks a class up without throwing; a miss is reported as a skipped hook. */
    private static Class<?> findClassIfExists(String className, ClassLoader classLoader,
            String label, Stats stats) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            stats.skipped++;
            XposedBridge.log(TAG + label + " skipped: class " + className + " not found");
        }
        return clazz;
    }

    /**
     * Hooks every overload of the given method and logs whether it was installed or skipped,
     * so a missing method never fails silently.
     */
    private static void hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback,
            String label, Stats stats) {
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(clazz, methodName, callback);
        int count = unhooks == null ? 0 : unhooks.size();
        if (count > 0) {
            stats.installed += count;
            XposedBridge.log(TAG + label + " installed: " + clazz.getName() + "#" + methodName
                    + " (" + count + " overload(s))");
        } else {
            stats.skipped++;
            XposedBridge.log(TAG + label + " skipped: " + clazz.getName() + "#" + methodName
                    + " not found");
        }
    }

    private static void logSummary(XC_LoadPackage.LoadPackageParam lpparam, Stats stats) {
        XposedBridge.log(TAG + "hooks in " + lpparam.processName
                + ": installed=" + stats.installed + ", skipped=" + stats.skipped);
    }
}
