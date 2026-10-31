package app.franticg33k.extension.creatorsapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import android.os.Looper;
import android.widget.Adapter;
import android.widget.ListView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * One flow, three entry points. The flow: get onto the camera's Wi-Fi (scan its QR, or reconnect with the
 * credentials from the last scan), then let the app's own one-time connection run, and select the ZV-E10 in its
 * search list for you.
 *
 *  - {@link #intercept}: "Connect only via Wi-Fi" in the device menu.
 *  - {@link #addPickerEntry} / {@link #onPickerClick}: a plain "ZV-E10" row in the first-time model picker, whose
 *    stock guide is the Bluetooth pairing the ZV-E10 cannot complete. The row closes the picker and, once the
 *    Cameras tab is back, starts the search from the tab's own controller ({@link #registerMenu},
 *    {@link #onTabResume}); starting it from the picker skipped the tab's bookkeeping and left Remote Shooting
 *    refusing taps.
 *  - {@link #onSearchStart}: runs when the app's search screen opens; selects the ZV-E10 when it appears.
 */
@SuppressWarnings("unused")
public final class JoinFlow {
    private static final String MODEL = "ZV-E10";
    private static final String SUPPORTED_CAMERA =
            "jp.co.sony.ips.portalapp.database.realm.SupportedCameraObject";
    private static final String PREFS = "zve10_join";
    private static final long AUTO_SELECT_WINDOW_MS = 90_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean passthrough;
    private static volatile boolean pendingSearch;
    private static volatile long autoSelectUntil;
    private static Object menuController; // DeviceMenuController of the Cameras tab, kept to start its one-time search
    private static ConnectivityManager.NetworkCallback callback; // must stay registered or Android drops the network

    private JoinFlow() {}

    // ---------------------------------------------------------------- device menu: "Connect only via Wi-Fi"

    /** Hook at the start of DeviceMenuSettingsController.onetimeConnection(). True = we handle it, skip the stock body. */
    public static boolean intercept(Object controller) {
        if (passthrough) {
            passthrough = false;
            return false;
        }
        try {
            Activity activity = (Activity) fieldOf(controller, "activity");
            MAIN.post(() -> chooser(activity, () -> proceed(controller)));
            return true;
        } catch (Throwable t) {
            return false; // stock behaviour
        }
    }

    /** Run the original onetimeConnection() once, bypassing this hook. */
    private static void proceed(Object controller) {
        autoSelectUntil = System.currentTimeMillis() + AUTO_SELECT_WINDOW_MS;
        passthrough = true;
        try {
            Method m = controller.getClass().getDeclaredMethod("onetimeConnection");
            m.setAccessible(true);
            m.invoke(controller);
        } catch (Throwable t) {
            passthrough = false;
        }
    }

    // ---------------------------------------------------------------- model picker: a "ZV-E10" row

    /** Hook in SupportCameraListAdapter after it copies the supported-camera list: add the original ZV-E10. */
    public static void addPickerEntry(ArrayList<Object> list) {
        try {
            Class<?> c = Class.forName(SUPPORTED_CAMERA);
            for (Object o : list) {
                if (MODEL.equals(c.getMethod("realmGet$modelName").invoke(o))) return; // Sony added it themselves
            }
            Object o = c.getConstructor().newInstance();
            c.getMethod("realmSet$modelName", String.class).invoke(o, MODEL);
            c.getMethod("realmSet$category", String.class).invoke(o, "VLOGCAM");
            // no promotionName: the row then shows just the model name, "ZV-E10"
            list.add(o);
        } catch (Throwable t) {
            // stock list
        }
    }

    /** Hook at the start of SupportCameraFragment's item click listener. True = the ZV-E10 row, we handle it. */
    public static boolean onPickerClick(Object listener, Object clickItem) {
        try {
            String name = (String) clickItem.getClass().getMethod("getDisplayedModelName").invoke(clickItem);
            if (!MODEL.equals(name)) return false;
            Object fragment = fieldOf(listener, "this$0");
            Activity activity = (Activity) fragment.getClass().getMethod("requireActivity").invoke(fragment);
            MAIN.post(() -> chooser(activity, () -> backToTabAndSearch(activity)));
            return true;
        } catch (Throwable t) {
            return false; // stock behaviour (the Bluetooth guide)
        }
    }

    /** Close the picker; onTabResume() then starts the search from the Cameras tab's own controller. */
    private static void backToTabAndSearch(Activity picker) {
        autoSelectUntil = System.currentTimeMillis() + AUTO_SELECT_WINDOW_MS;
        pendingSearch = true;
        picker.finish();
    }

    /** Hook at the start of DeviceMenuController.onPrepareOptionsMenu: remember the tab's controller. */
    public static void registerMenu(Object controller) {
        menuController = controller;
    }

    /** Hook at the start of FunctionModeController.onResume (the Cameras tab coming back to the front). */
    public static void onTabResume() {
        if (!pendingSearch) return;
        MAIN.postDelayed(() -> {
            if (!pendingSearch) return;
            Object menu = menuController;
            if (menu == null) return; // try again on the next resume
            pendingSearch = false;
            try {
                Object oneTime = fieldOf(menu, "oneTimeConnectionController");
                oneTime.getClass().getMethod("startDeviceConnectionActivity").invoke(oneTime);
            } catch (Throwable t) {
                // the user can still use the gear menu
            }
        }, 400);
    }

    // ---------------------------------------------------------------- search screen: pick the ZV-E10 for the user

    /** Hook at the start of SearchedDeviceListController.onStart(). */
    public static void onSearchStart(Object controller) {
        if (System.currentTimeMillis() > autoSelectUntil) return;
        long deadline = autoSelectUntil;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (System.currentTimeMillis() > deadline) return;
                try {
                    Object view = fieldOf(controller, "viewController");
                    ListView list = view == null ? null : (ListView) fieldOf(view, "listView");
                    Adapter a = list == null ? null : list.getAdapter();
                    if (a != null) {
                        int found = -1, zv = 0;
                        for (int i = 0; i < a.getCount(); i++) {
                            if (ZvE10.isZvE10Dd(a.getItem(i))) { zv++; if (found < 0) found = i; }
                        }
                        // exactly one ZV-E10: it is the camera whose QR was just scanned
                        if (zv == 1 && found >= 0) {
                            autoSelectUntil = 0;
                            final int pos = found;
                            list.post(() -> list.performItemClick(
                                    list.getChildAt(pos - list.getFirstVisiblePosition()), pos, a.getItemId(pos)));
                            return;
                        }
                    }
                } catch (Throwable t) {
                    // not ready yet, or the screen changed: keep polling until the window closes
                }
                MAIN.postDelayed(this, 500);
            }
        });
    }

    // ---------------------------------------------------------------- shared: chooser -> scan / reconnect -> join

    private static void chooser(Activity a, Runnable next) {
        SharedPreferences sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ssid = sp.getString("ssid", null), pass = sp.getString("pass", null);
        boolean saved = ssid != null && pass != null;
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (saved) {
            labels.add("Reconnect to " + ssid);
            actions.add(() -> join(a, ssid, pass, next));
        }
        labels.add("Scan the camera's QR code");
        actions.add(() -> scan(a, next));
        labels.add("Already connected to the camera's Wi-Fi");
        actions.add(next);
        new AlertDialog.Builder(a)
                .setTitle("Connect to the camera's Wi-Fi")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void scan(Activity a, Runnable next) {
        QrScanner.show(a, new QrScanner.Callback() {
            @Override public void onText(String text) {
                CameraQr qr = CameraQr.parse(text);
                if (qr == null) { toast(a, "That isn't a Sony camera QR code"); return; }
                join(a, qr.ssid, qr.pass, next);
            }
            @Override public void onCancel() {}
        });
    }

    private static void join(Activity a, String ssid, String pass, Runnable next) {
        ConnectivityManager cm = a.getSystemService(ConnectivityManager.class);
        // Deliberately no setBssid(): the QR's M field is the camera's device address, but a Wi-Fi Direct
        // hotspot broadcasts a variant of it (seen on a ZV-E10: QR dc:fe:23:0f:14:15, AP de:fe:23:0f:94:15).
        // Pinning it made Android find no matching network and time out. The DIRECT-<id> SSID is unique enough.
        WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid).setWpa2Passphrase(pass).build();
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec).build();
        if (callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        }
        toast(a, "Joining " + ssid + "… approve the Wi-Fi prompt");
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                // ponytail: bind the whole process so the app's own discovery sockets use the camera network
                cm.bindProcessToNetwork(n);
                // remember it only once it worked, so a wrong password is never kept
                a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("ssid", ssid).putString("pass", pass).apply();
                MAIN.post(next);
            }
            @Override public void onUnavailable() {
                toast(a, "Couldn't join " + ssid + ". Keep the camera's QR screen open and Location on.");
            }
        };
        cm.requestNetwork(req, callback, 60_000);
    }

    private static Object fieldOf(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void toast(Activity a, String msg) {
        MAIN.post(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
    }
}
