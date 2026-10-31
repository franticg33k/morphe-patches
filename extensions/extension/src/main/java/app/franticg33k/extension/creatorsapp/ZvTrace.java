package app.franticg33k.extension.creatorsapp;

import android.util.Log;

import java.util.List;

/** Debug-only: logs the PTP/IP session the app has with the camera, tag ZVPATCH. Release builds log nothing. */
@SuppressWarnings("unused")
public final class ZvTrace {
    private static final String TAG = "ZVPATCH";

    private ZvTrace() {}

    public static void ok(Object op, Object params) {
        Log.i(TAG, "OK      " + op + " params=" + params);
    }

    public static void fail(Object op, Object response) {
        Log.w(TAG, "FAIL    " + op + " response=" + response);
    }

    public static void timeout(Object op) {
        Log.w(TAG, "TIMEOUT " + op);
    }

    public static void probeFailed(Object op) {
        Log.w(TAG, "PROBE-FAIL " + op);
    }

    /** About to send: logs the executor's current transaction (its toString names the operation). */
    public static void sending(Object executor) {
        try {
            java.lang.reflect.Field f = executor.getClass().getDeclaredField("mCurrentTransaction");
            f.setAccessible(true);
            Log.i(TAG, "SEND    " + f.get(executor));
        } catch (Throwable t) {
            Log.i(TAG, "SEND    (unreadable: " + t + ")");
        }
    }

    public static void teardown() {
        Log.w(TAG, "TEARDOWN session closed", new Throwable("who closed the session"));
    }

    public static void function(Object f) { Log.i(TAG, "CameraConnector.switchFunctionMode " + f); }

    public static void initMode(Object mode) { mode("PtpIpCamera.initialize", mode); }

    public static void switchMode(Object mode) { mode("PtpIpCamera.switchFunctionMode", mode); }

    public static void mode(String where, Object mode) {
        Log.i(TAG, where + " mode=" + mode);
    }
}
