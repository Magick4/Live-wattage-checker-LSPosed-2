package com.you.chargestatus;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Live Wattage Checker - an LSPosed (Xposed) module.
 *
 * Hooks the SystemUI status bar clock and inserts a small TextView right next
 * to it that shows the live charging power (V x A) read from the battery's
 * power_supply sysfs nodes, e.g.  "⚡6.45W".
 *
 * Designed for AOSP / near-stock ROMs (Android 12-14). The status bar clock is
 * com.android.systemui.statusbar.policy.Clock, inflated from status_bar.xml
 * directly inside a LinearLayout (status_bar_left_side). On LineageOS-style
 * ROMs several Clock views exist at once, so the label is attached only to the
 * default one (@+id/clock) - see {@link #isPrimaryClock(android.view.View)}.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String LOG_TAG = "LiveWattage";
    private static final String LABEL_TAG = "live_wattage_label";
    private static final String CLOCK_CLASS = "com.android.systemui.statusbar.policy.Clock";
    /** Resource entry name of the one clock we attach to (@+id/clock). */
    private static final String CLOCK_ID_NAME = "clock";

    // Some OEMs expose the same battery nodes under /sys/class/power_supply/main/
    // instead of /battery/. First readable path wins.
    private static final List<String> VOLTAGE_PATHS = Arrays.asList(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/main/voltage_now");
    private static final List<String> CURRENT_PATHS = Arrays.asList(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/main/current_now");
    private static final List<String> STATUS_PATHS = Arrays.asList(
            "/sys/class/power_supply/battery/status",
            "/sys/class/power_supply/main/status");

    private static final int REFRESH_MS = 1500;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedBridge.log(LOG_TAG + ": hooked SystemUI");

        final XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    injectWattLabel((View) param.thisObject);
                } catch (Throwable t) {
                    XposedBridge.log(LOG_TAG + ": injection error: " + t);
                }
            }
        };

        boolean hooked = false;
        // On stock AOSP, onFinishInflate fires BEFORE the clock is added to its
        // parent, so hook onAttachedToWindow too - it fires once the parent
        // hierarchy exists. A per-parent tag guard prevents double injection.
        for (String method : new String[]{"onFinishInflate", "onAttachedToWindow"}) {
            try {
                XposedHelpers.findAndHookMethod(CLOCK_CLASS, lpparam.classLoader, method, hook);
                hooked = true;
            } catch (Throwable t) {
                XposedBridge.log(LOG_TAG + ": could not hook " + method + ": " + t);
            }
        }
        if (!hooked) {
            XposedBridge.log(LOG_TAG + ": Clock class not found - this ROM probably uses a "
                    + "custom clock class, the label can't be injected.");
        }
    }

    private void injectWattLabel(View clockView) {
        // LineageOS-style ROMs inflate three Clock instances (@+id/clock,
        // @+id/center_clock, @+id/right_clock) and hide the two the user did
        // not pick. The Clock hook fires for all three, so without this gate
        // the module renders three wattage labels.
        if (!isPrimaryClock(clockView)) return;

        ViewGroup parent = (ViewGroup) clockView.getParent();
        if (parent == null) return; // not attached yet - onAttachedToWindow will retry

        // Guard against duplicate injection (two hook points / view recreation).
        if (parent.findViewWithTag(LABEL_TAG) != null) return;

        // Window-wide guard: the clock and an already-injected label can end up
        // in different containers after a theme/config change re-inflates part
        // of the status bar, which the parent-level check above would miss.
        View root = clockView.getRootView();
        if (root instanceof ViewGroup && ((ViewGroup) root).findViewWithTag(LABEL_TAG) != null) {
            return;
        }

        // Find a LinearLayout to insert into. The stock status bar clock sits
        // directly inside a LinearLayout (status_bar_left_side); on other ROMs
        // it may be wrapped, so walk up until we find one.
        ViewGroup container = parent;
        while (!(container instanceof LinearLayout)) {
            if (!(container.getParent() instanceof ViewGroup)) {
                XposedBridge.log(LOG_TAG + ": no LinearLayout container found, skipping");
                return;
            }
            container = (ViewGroup) container.getParent();
        }
        if (container.findViewWithTag(LABEL_TAG) != null) return;

        final Context ctx = clockView.getContext();
        final TextView wattLabel = new TextView(ctx);
        wattLabel.setTag(LABEL_TAG);
        wattLabel.setSingleLine(true);
        wattLabel.setGravity(Gravity.CENTER_VERTICAL);
        wattLabel.setPadding(dp2px(ctx, 4), 0, dp2px(ctx, 2), 0);
        wattLabel.setText("");

        // Match the clock's look so the label blends in.
        if (clockView instanceof TextView) {
            TextView clockTv = (TextView) clockView;
            wattLabel.setTypeface(clockTv.getTypeface());
            wattLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, clockTv.getTextSize());
            wattLabel.setTextColor(clockTv.getCurrentTextColor());
        } else {
            wattLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            wattLabel.setTextColor(Color.WHITE);
        }

        int clockIndex = container.indexOfChild(clockView);
        if (clockIndex < 0) {
            // Clock is nested deeper inside the container - find the innermost
            // child that still contains the clock and insert right after it.
            View inner = clockView;
            ViewGroup vg = (ViewGroup) clockView.getParent();
            while (vg != null && vg != container) {
                inner = vg;
                vg = (ViewGroup) vg.getParent();
            }
            if (vg == null) return;
            clockIndex = container.indexOfChild(inner);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        container.addView(wattLabel, clockIndex + 1, lp);

        XposedBridge.log(LOG_TAG + ": label injected after @id/" + CLOCK_ID_NAME);

        // Background reader thread - never blocks the UI thread.
        final HandlerThread bgThread = new HandlerThread("LiveWattageReader");
        bgThread.start();
        final Handler bgHandler = new Handler(bgThread.getLooper());
        final Handler uiHandler = new Handler(ctx.getMainLooper());

        // Stop polling once the label goes away (status bar re-inflation),
        // otherwise every re-injection would stack another live thread.
        wattLabel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                bgHandler.removeCallbacksAndMessages(null);
                bgThread.quitSafely();
            }
        });

        bgHandler.post(new Runnable() {
            @Override
            public void run() {
                final String label = computeLabel(ctx);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (wattLabel.isAttachedToWindow()) {
                            wattLabel.setText(label);
                        }
                    }
                });
                bgHandler.postDelayed(this, REFRESH_MS);
            }
        });
    }

    /**
     * True only for the status bar's default left clock, {@code @+id/clock}.
     *
     * LineageOS (and derivatives such as crDroid / Evolution X) ship three
     * Clock views in status_bar.xml - {@code @+id/clock},
     * {@code @+id/center_clock} and {@code @+id/right_clock} - and toggle
     * visibility based on the user's clock position setting. Hooking the Clock
     * class fires for every one of them, so injection has to be restricted to
     * the canonical one or the user sees the wattage three times.
     *
     * Unknown/obfuscated ids fall through as "allowed" so that ROMs which
     * name their clock differently keep working as before.
     */
    private static boolean isPrimaryClock(View clockView) {
        final int id = clockView.getId();
        if (id == View.NO_ID) {
            // Nothing to discriminate on - let the duplicate guards decide.
            return true;
        }
        try {
            Resources res = clockView.getResources();
            int expected = res.getIdentifier(
                    CLOCK_ID_NAME, "id", clockView.getContext().getPackageName());
            if (expected != 0) {
                if (id == expected) return true;
                XposedBridge.log(LOG_TAG + ": skipping secondary clock @id/"
                        + safeIdName(res, id));
                return false;
            }
            // Package lookup failed - compare the entry name directly.
            String name = res.getResourceEntryName(id);
            if (CLOCK_ID_NAME.equals(name)) return true;
            XposedBridge.log(LOG_TAG + ": skipping secondary clock @id/" + name);
            return false;
        } catch (Throwable t) {
            // Resource not resolvable - can't tell them apart, allow it.
            return true;
        }
    }

    private static String safeIdName(Resources res, int id) {
        try {
            return res.getResourceEntryName(id);
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private String computeLabel(Context ctx) {
        try {
            Long voltageUv = readLong(VOLTAGE_PATHS);
            Long currentUa = readLong(CURRENT_PATHS);
            String status = readString(STATUS_PATHS);

            // Fallbacks for devices where sysfs is not readable from SystemUI.
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                        if (voltageUv == null) {
                // BatteryManager has no public voltage property; read the
                // sticky ACTION_BATTERY_CHANGED broadcast instead.
                try {
                    Intent bat = ctx.registerReceiver(null,
                            new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (bat != null) {
                        int v = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                        if (v > 0) voltageUv = (long) v * 1000L; // mV -> uV
                    }
                } catch (Throwable ignored) {
                }
                        }
            if (currentUa == null && bm != null) {
                int c = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (c != 0 && c != Integer.MIN_VALUE) currentUa = (long) c; // uA
            }
            if (status == null || status.isEmpty()) {
                status = batteryStatusString(ctx);
            }

            if (voltageUv == null || currentUa == null) {
                XposedBridge.log(LOG_TAG + ": no voltage/current source available"
                        + " (voltage=" + voltageUv + " current=" + currentUa + ")");
                return "";
            }

            long vMv = voltageUv / 1000;                 // uV -> mV
            long iMa = Math.abs(currentUa / 1000);       // uA -> mA
            long pMw = vMv * iMa / 1000;                 // mW

            long wInt = pMw / 1000;
            long wDec = (pMw % 1000) / 10;               // 2 digits

            boolean charging = status != null && status.contains("Charging");
            if (!charging && currentUa != 0 && (status == null || status.isEmpty())) {
                charging = true; // no status info - assume flowing current = charging
            }

            return charging ? "\u26A1" + wInt + "." + pad2(wDec) + "W" : "";
        } catch (Throwable t) {
            XposedBridge.log(LOG_TAG + ": read error: " + t);
            return "";
        }
    }

    private String batteryStatusString(Context ctx) {
        try {
            Intent bat = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat == null) return null;
            int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING:     return "Charging";
                case BatteryManager.BATTERY_STATUS_FULL:         return "Full";
                case BatteryManager.BATTERY_STATUS_DISCHARGING:  return "Discharging";
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
                default: return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pad2(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }

    private static int dp2px(Context ctx, int dp) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * dp);
    }

    private Long readLong(List<String> paths) {
        String s = readString(paths);
        if (s == null) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String readString(List<String> paths) {
        for (String path : paths) {
            try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) return line.trim();
            } catch (Exception ignored) {
                // try next path
            }
        }
        return null;
    }
}
