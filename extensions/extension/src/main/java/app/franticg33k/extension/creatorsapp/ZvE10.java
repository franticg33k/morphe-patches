package app.franticg33k.extension.creatorsapp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Helpers for the ZV-E10 patches. Everything is reflective so this class compiles without Sony's
 * classes; the field/method names used are the app's own (the app is not obfuscated) and any
 * lookup failure degrades to "not a ZV-E10" / "do the stock thing".
 */
@SuppressWarnings("unused")
public final class ZvE10 {
    private static final String APP = "jp.co.sony.ips.portalapp.";
    private static final String MODEL = "ZV-E10";

    private ZvE10() {}

    private static Object field(Object o, Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Object call(Object o, String name) throws Exception {
        Method m = o.getClass().getMethod(name);
        return m.invoke(o);
    }

    private static Object didOf(Object dd) throws Exception {
        return field(dd, Class.forName(APP + "common.device.DeviceDescription"), "mDidXml");
    }

    private static Object ddOfCamera(Object camera) throws Exception {
        return field(camera, Class.forName(APP + "camera.BaseCamera"), "mDdXml");
    }

    /** DigitalImagingDescription -> is the model name ZV-E10. */
    public static boolean isZvE10Did(Object did) {
        try {
            if (did == null) return false;
            Object info = call(did, "getDeviceInfo");
            return info != null && MODEL.equals(call(info, "getModelName"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** DeviceDescription -> is the model name ZV-E10. */
    public static boolean isZvE10Dd(Object dd) {
        try {
            return dd != null && isZvE10Did(didOf(dd));
        } catch (Throwable t) {
            return false;
        }
    }

    /** ZV-E10 whose DID reports no media server: the PC Remote service (no storage exposed). */
    private static boolean isPcRemoteDd(Object dd) {
        try {
            return isZvE10Dd(dd) && !((Boolean) call(didOf(dd), "isMediaServerSupportEnabled"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPcRemoteCamera(Object camera) {
        try {
            return isPcRemoteDd(ddOfCamera(camera));
        } catch (Throwable t) {
            return false;
        }
    }

    /** P1: add the ZV-E10 to the (local copy of the) supported-camera list. */
    public static void addModel(ArrayList<Object> list) {
        try {
            Class<?> c = Class.forName(APP + "database.realm.SupportedCameraObject");
            Object o = c.getConstructor().newInstance();
            c.getMethod("realmSet$modelName", String.class).invoke(o, MODEL);
            list.add(o);
        } catch (Throwable t) {
            // stock behaviour: camera stays unsupported
        }
    }

    /** P3: FunctionModeController.onResume. Skip the switch to contents transfer when it cannot work. */
    public static void switchFunctionMode(Object connector, Object function, Object controller) {
        try {
            Object camera = controller.getClass().getMethod("getCamera").invoke(controller);
            String n = camera.getClass().getName();
            boolean keep = n.endsWith(".PtpUsbCamera") || isPcRemoteCamera(camera);
            if (keep) return;
        } catch (Throwable t) {
            // fall through to the stock call
        }
        try {
            for (Method m : connector.getClass().getMethods()) {
                if (m.getName().equals("switchFunctionMode") && m.getParameterTypes().length == 1) {
                    m.invoke(connector, function);
                    return;
                }
            }
        } catch (Throwable t) {
            // nothing sensible left to do
        }
    }

    /** P7: PtpIpCamera.initialize. A PC Remote session always opens in remote-control mode. */
    public static Object functionMode(Object camera, Object mode) {
        if (!isPcRemoteCamera(camera)) return mode;
        try {
            Class<?> e = Class.forName(APP + "ptpip.base.transaction.EnumFunctionMode");
            return e.getField("REMOTE_CONTROL_MODE").get(null);
        } catch (Throwable t) {
            return mode;
        }
    }

    /** P7: PtpIpCamera.switchFunctionMode. A PC Remote session never leaves remote-control mode. */
    public static boolean refuseSwitch(Object camera, Object mode) {
        if (!isPcRemoteCamera(camera)) return false;
        try {
            Class<?> e = Class.forName(APP + "ptpip.base.transaction.EnumFunctionMode");
            return mode != e.getField("REMOTE_CONTROL_MODE").get(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** P7: selectedConnectDevice$1 contents-transfer gate; the ZV-E10 passes so PC Remote is selectable. */
    public static boolean isContentsTransferAvailable(Object did) {
        if (isZvE10Did(did)) return true;
        try {
            return (Boolean) call(did, "isContentsTransferAvailable");
        } catch (Throwable t) {
            return false;
        }
    }

    /** P9: BaseCamera.setLiveViewStreamCallback() is package-private; it self-checks, so just call it. */
    public static void startLiveView(Object camera) {
        try {
            Method m = Class.forName(APP + "camera.BaseCamera").getDeclaredMethod("setLiveViewStreamCallback");
            m.setAccessible(true);
            m.invoke(camera);
        } catch (Throwable t) {
            // stock behaviour
        }
    }
}
