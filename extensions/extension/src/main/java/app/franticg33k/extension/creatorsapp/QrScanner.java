package app.franticg33k.extension.creatorsapp;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.franticg33k.extension.zxing.BarcodeFormat;
import app.franticg33k.extension.zxing.BinaryBitmap;
import app.franticg33k.extension.zxing.DecodeHintType;
import app.franticg33k.extension.zxing.PlanarYUVLuminanceSource;
import app.franticg33k.extension.zxing.common.HybridBinarizer;
import app.franticg33k.extension.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Full-screen live QR scanner built without any layout XML, so it can live in a dex-only extension.
 * Camera2 feeds a preview TextureView and a YUV ImageReader; frames are decoded with ZXing's
 * QRCodeReader on a background thread. QR detection is rotation-invariant, so frames are not rotated.
 */
final class QrScanner {
    interface Callback {
        void onText(String text);
        void onCancel();
    }

    private static final int PREVIEW_W = 1280;
    private static final int PREVIEW_H = 720;
    private static final int REQUEST_CODE = 4711;
    private static final long PERMISSION_WAIT_MS = 60_000;

    private final Activity activity;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final TextView hint;
    private final TextureView texture;
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile boolean done;
    private long lastDecode;
    private final QRCodeReader qr = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    static void show(Activity activity, Callback callback) {
        new QrScanner(activity, callback).start();
    }

    private QrScanner(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(activity);
        texture = new TextureView(activity);
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        boolean portrait = dm.heightPixels >= dm.widthPixels;
        // Cover the screen with the preview's aspect ratio (the system rotates the buffer for the display).
        float ar = portrait ? (float) PREVIEW_H / PREVIEW_W : (float) PREVIEW_W / PREVIEW_H;
        int w = dm.widthPixels, h = Math.round(w / ar);
        if (h < dm.heightPixels) { h = dm.heightPixels; w = Math.round(h * ar); }
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(w, h, Gravity.CENTER);
        root.addView(texture, tp);

        hint = new TextView(activity);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(16);
        hint.setBackgroundColor(0x99000000);
        hint.setPadding(32, 24, 32, 24);
        hint.setText("Point the camera at the QR code shown on the camera's screen");
        root.addView(hint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        Button cancel = new Button(activity);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish(null));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cp.bottomMargin = 96;
        root.addView(cancel, cp);

        dialog.setContentView(root);
        dialog.setOnCancelListener(d -> finish(null));
    }

    private void start() {
        dialog.show();
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openWhenReady();
            return;
        }
        activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CODE);
        hint.setText("Allow camera access to scan the QR code");
        // ponytail: the permission result goes to the Activity, which we don't own; poll the state instead.
        long deadline = System.currentTimeMillis() + PERMISSION_WAIT_MS;
        main.post(new Runnable() {
            @Override public void run() {
                if (done) return;
                if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    hint.setText("Point the camera at the QR code shown on the camera's screen");
                    openWhenReady();
                } else if (System.currentTimeMillis() > deadline) {
                    finish(null);
                } else {
                    main.postDelayed(this, 300);
                }
            }
        });
    }

    private void openWhenReady() {
        if (texture.isAvailable()) { openCamera(); return; }
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }

    private void openCamera() {
        if (done || camera != null) return;
        try {
            thread = new HandlerThread("qr-scan");
            thread.start();
            bg = new Handler(thread.getLooper());
            CameraManager cm = (CameraManager) activity.getSystemService(Activity.CAMERA_SERVICE);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            if (id == null) { fail("No back camera"); return; }
            reader = ImageReader.newInstance(PREVIEW_W, PREVIEW_H, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onFrame, bg);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { camera = d; startSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); fail("Camera error " + e); }
            }, bg);
        } catch (CameraAccessException | SecurityException e) {
            fail("Cannot open the camera");
        }
    }

    @SuppressWarnings("deprecation") // ponytail: the SessionConfiguration API needs API 28; this works everywhere
    private void startSession() {
        try {
            SurfaceTexture st = texture.getSurfaceTexture();
            st.setDefaultBufferSize(PREVIEW_W, PREVIEW_H);
            Surface preview = new Surface(st);
            Surface frames = reader.getSurface();
            CaptureRequest.Builder rb = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            rb.addTarget(preview);
            rb.addTarget(frames);
            rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(preview, frames), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try { s.setRepeatingRequest(rb.build(), null, bg); } catch (Exception e) { fail("Camera preview failed"); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) { fail("Camera preview failed"); }
            }, bg);
        } catch (Exception e) {
            fail("Camera preview failed");
        }
    }

    private void onFrame(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            long now = System.currentTimeMillis();
            if (done || now - lastDecode < 150) return; // ponytail: ~6 decodes/s is plenty for a QR
            lastDecode = now;
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int w = img.getWidth(), h = img.getHeight(), stride = y.getRowStride();
            byte[] lum = new byte[w * h];
            for (int row = 0; row < h; row++) {
                buf.position(row * stride);
                buf.get(lum, row * w, w);
            }
            try {
                String text = qr.decode(new BinaryBitmap(new HybridBinarizer(
                        new PlanarYUVLuminanceSource(lum, w, h, 0, 0, w, h, false))), hints).getText();
                finish(text);
            } catch (app.franticg33k.extension.zxing.ReaderException ignored) {
                // no QR in this frame
            } finally {
                qr.reset();
            }
        } finally {
            img.close();
        }
    }

    private void fail(String message) {
        main.post(() -> {
            if (done) return;
            hint.setText(message);
            main.postDelayed(() -> finish(null), 1500);
        });
    }

    private void finish(String text) {
        if (done) return;
        done = true;
        main.post(() -> {
            try { if (session != null) session.close(); } catch (Exception ignored) {}
            try { if (camera != null) camera.close(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            if (thread != null) thread.quitSafely();
            dialog.dismiss();
            if (text != null) callback.onText(text); else callback.onCancel();
        });
    }
}
