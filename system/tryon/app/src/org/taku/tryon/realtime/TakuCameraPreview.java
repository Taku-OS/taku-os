// SPDX-License-Identifier: Apache-2.0
package org.taku.tryon.realtime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Camera2 preview, with explicitly opt-in memory-only direct frames for confirmed shopping sessions. */
final class TakuCameraPreview extends FrameLayout {
    interface Listener { void changed(String detail, boolean showingFrames); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextureView texture;
    private final CameraManager manager;
    private final DisplayManager displays;
    private final Listener listener;
    private final boolean directFrames;
    private static final AtomicLong STREAMS = new AtomicLong();
    private final long directStream = STREAMS.incrementAndGet();
    private volatile CameraPreviewFrames frames;
    private boolean framesClosing, listeningToDisplay;
    private final ArrayList<Runnable> closedCallbacks = new ArrayList<>();
    private CameraDevice device, closingDevice;
    private CameraCaptureSession session;
    private Surface surface;
    private Size buffer;
    private int sensorOrientation, epoch, openingTicket = -1;
    private boolean desired, opening, disposed, showingFrames;
    // Monotonic sensor metadata only; allows continuity checks even while the video is minimized.
    volatile long lastSensorTimestampNs;
    private final boolean mirrorInView = Build.VERSION.SDK_INT >= 33;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (getDisplay() != null && displayId == getDisplay().getDisplayId()) updateTransform();
        }
    };

    TakuCameraPreview(Context context, Listener listener) {
        this(context, listener, false);
    }

    TakuCameraPreview(Context context, Listener listener, boolean directFrames) {
        super(context); this.listener = listener; this.directFrames = directFrames;
        manager = context.getSystemService(CameraManager.class); displays = context.getSystemService(DisplayManager.class);
        setBackgroundColor(Color.rgb(21, 22, 22));
        texture = new TextureView(context); texture.setOpaque(true);
        texture.setContentDescription("Taku live camera preview, preserving the video aspect ratio");
        addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        texture.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateTransform());
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture value, int width, int height) { openIfReady(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture value, int width, int height) { updateTransform(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture value) { close(null); return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture value) { }
        });
    }

    void start() {
        if (disposed) return;
        if (directFrames && !listeningToDisplay && displays != null) {
            displays.registerDisplayListener(displayListener, main); listeningToDisplay = true;
        }
        desired = true; openIfReady();
    }

    /** Completion means CameraDevice.onClosed has happened, including a late in-flight open callback. */
    void close(Runnable afterClosed) {
        desired = false; epoch++; showingFrames = false;
        if (listeningToDisplay && displays != null) { displays.unregisterDisplayListener(displayListener); listeningToDisplay = false; }
        if (afterClosed != null) closedCallbacks.add(afterClosed);
        if (session != null) { session.close(); session = null; }
        stopDirectFrames();
        if (device != null) {
            CameraDevice previous = device; device = null; closingDevice = previous; previous.close();
        }
        finishCloseIfReady();
    }

    void dispose() { disposed = true; close(null); }
    boolean isCameraOpen() { return device != null && showingFrames; }
    boolean hasCameraOwnership() { return opening || device != null || closingDevice != null; }

    /** Bounded, upright selfie JPEG. Display-only construction never creates a frame reader. */
    CallTryOnBridge.Frame latestDirectFrame(long afterSequence) {
        CameraPreviewFrames current = frames;
        if (current == null) return null;
        if (getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            main.post(() -> {
                if (getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) close(null);
            });
            return null;
        }
        return current.latest(afterSequence);
    }

    /** Privileged dumpsys metadata; no video pixels, filenames or session credentials. */
    String continuityDiagnostic() {
        return "cameraEpoch=" + epoch + " cameraDeviceIdentity=" + (device == null ? 0 : System.identityHashCode(device))
                + " cameraSessionIdentity=" + (session == null ? 0 : System.identityHashCode(session))
                + " cameraSurfaceIdentity=" + (surface == null ? 0 : System.identityHashCode(surface))
                + " sensorTimestampNs=" + lastSensorTimestampNs + " cameraOwnership=" + hasCameraOwnership();
    }

    private void openIfReady() {
        if (!desired || disposed || opening || device != null || closingDevice != null || framesClosing || !texture.isAvailable()) return;
        if (getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.changed("Allow camera access, then open try-on preview.", false); return;
        }
        final int ticket = epoch;
        try {
            String front = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    Size candidate = chooseBuffer(characteristics, directFrames);
                    if (candidate == null) continue;
                    front = id; buffer = candidate;
                    Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    sensorOrientation = orientation == null ? 0 : orientation;
                    break;
                }
            }
            if (front == null) { listener.changed("No front camera is available. Check this device's camera settings.", false); return; }
            SurfaceTexture target = texture.getSurfaceTexture();
            if (target == null) return;
            target.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
            if (directFrames) {
                long cameraIdentity = front.hashCode() & 0xffff_ffffL;
                frames = new CameraPreviewFrames(getContext(), buffer.getWidth(), buffer.getHeight(),
                        cameraIdentity, directStream, directRotation(), main, () -> {
                            if (ticket == epoch && desired) {
                                listener.changed("Camera video is unavailable. Close the preview and try again.", false); close(null);
                            }
                        });
            }
            surface = new Surface(target); opening = true; openingTicket = ticket; updateTransform();
            listener.changed("Opening the local camera. Cloud processing has not started…", false);
            manager.openCamera(front, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    boolean ownedOpening = opening && openingTicket == ticket;
                    if (ownedOpening) opening = false;
                    if (!ownedOpening || !desired || disposed || ticket != epoch) {
                        if (ownedOpening) closingDevice = camera;
                        camera.close(); return;
                    }
                    device = camera; configure(camera, ticket);
                }
                @Override public void onDisconnected(CameraDevice camera) { cameraFailed(camera, ticket, "Another app is using the camera. Return to Taku to reopen the preview."); }
                @Override public void onError(CameraDevice camera, int error) { cameraFailed(camera, ticket, "The camera is unavailable. Close other camera apps and try again."); }
                @Override public void onClosed(CameraDevice camera) {
                    if (closingDevice == camera) closingDevice = null;
                    if (device == camera) device = null;
                    finishCloseIfReady();
                }
            }, main);
        } catch (CameraAccessException | RuntimeException | OutOfMemoryError failure) {
            opening = false; desired = false;
            stopDirectFrames();
            listener.changed("Could not open the camera. Check camera permission and close other camera apps, then try again.", false);
            finishCloseIfReady();
        }
    }

    private static Size chooseBuffer(CameraCharacteristics characteristics, boolean directFrames) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return null;
        Size[] choices = map.getOutputSizes(SurfaceTexture.class);
        if (choices == null) return null;
        Size[] yuvChoices = directFrames ? map.getOutputSizes(ImageFormat.YUV_420_888) : null;
        if (directFrames && yuvChoices == null) return null;
        Size best = null; double bestScore = Double.MAX_VALUE;
        for (Size candidate : choices) {
            int width = candidate.getWidth(), height = candidate.getHeight();
            // The existing system frame bridge accepts at most 1280 x 960, with even chroma dimensions.
            if (width <= 0 || height <= 0 || width > 1280 || height > 960 || (width & 1) != 0 || (height & 1) != 0) continue;
            if (directFrames) {
                boolean supportsYuv = false;
                for (Size yuv : yuvChoices) if (candidate.equals(yuv)) { supportsYuv = true; break; }
                if (!supportsYuv) continue;
            }
            if (width == 640 && height == 480) return candidate;
            double score = Math.abs((double) width / height - 4d / 3d) * 10d + Math.abs(Math.log((double) width * height / (640d * 480d)));
            if (score < bestScore) { best = candidate; bestScore = score; }
        }
        return best;
    }

    private void configure(CameraDevice camera, int ticket) {
        try {
            OutputConfiguration output = new OutputConfiguration(surface);
            // AUTO mirrors front camera output on modern Android. Disable it before applying one
            // deliberate horizontal UI mirror, so the preview cannot be mirrored twice.
            if (mirrorInView) output.setMirrorMode(OutputConfiguration.MIRROR_MODE_NONE);
            ArrayList<OutputConfiguration> outputs = new ArrayList<>(); outputs.add(output);
            CameraPreviewFrames direct = frames;
            if (directFrames && direct == null) throw new IllegalStateException("direct_frames_missing");
            Surface directSurface = direct == null ? null : direct.surface();
            if (directSurface != null) {
                OutputConfiguration yuv = new OutputConfiguration(directSurface);
                if (Build.VERSION.SDK_INT >= 33) yuv.setMirrorMode(OutputConfiguration.MIRROR_MODE_NONE);
                outputs.add(yuv);
            }
            camera.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    outputs, main::post, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (disposed || !desired || ticket != epoch || device != camera) { configured.close(); return; }
                    session = configured;
                    try {
                        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        request.addTarget(surface);
                        if (directSurface != null) request.addTarget(directSurface);
                        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        configured.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override public void onCaptureCompleted(CameraCaptureSession owner, CaptureRequest request, TotalCaptureResult result) {
                                if (desired && !disposed && ticket == epoch && device == camera) {
                                    Long timestamp = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP);
                                    if (timestamp != null) lastSensorTimestampNs = timestamp;
                                }
                                if (!showingFrames && desired && !disposed && ticket == epoch && device == camera) {
                                    showingFrames = true; listener.changed("Camera ready. Choose an outfit, then start live try-on.", true);
                                }
                            }
                        }, main);
                    } catch (CameraAccessException | RuntimeException failure) {
                        cameraFailed(camera, ticket, "Could not start camera preview. Go back and try again.");
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    failed.close(); cameraFailed(camera, ticket, "This device could not set up camera preview. Close other camera apps and try again.");
                }
            }));
        } catch (CameraAccessException | RuntimeException failure) {
            cameraFailed(camera, ticket, "Could not set up camera preview. Go back and try again.");
        }
    }

    private void cameraFailed(CameraDevice camera, int ticket, String message) {
        boolean ownedOpening = opening && openingTicket == ticket;
        if (ownedOpening) opening = false;
        if (ticket != epoch && device != camera && closingDevice != camera) {
            // Even a cancelled open can fail before onOpened. Track it until onClosed so a handoff
            // callback cannot run while the framework is still releasing this camera handle.
            if (ownedOpening) closingDevice = camera;
            camera.close(); return;
        }
        if (ticket == epoch && !disposed && desired) { desired = false; listener.changed(message, false); }
        if (device == camera) device = null;
        if (session != null) { session.close(); session = null; }
        stopDirectFrames();
        showingFrames = false; closingDevice = camera; camera.close();
    }

    private void finishCloseIfReady() {
        if (opening || device != null || closingDevice != null || framesClosing) return;
        if (surface != null) { surface.release(); surface = null; }
        ArrayList<Runnable> callbacks = new ArrayList<>(closedCallbacks); closedCallbacks.clear();
        for (Runnable callback : callbacks) callback.run();
        if (desired && !disposed) openIfReady();
    }

    /** TextureView handles sensor orientation; compensate only display rotation and contain uniformly. */
    void updateTransform() {
        CameraPreviewFrames direct = frames;
        if (direct != null) direct.orientation(directRotation());
        if (buffer == null || texture.getWidth() <= 0 || texture.getHeight() <= 0) return;
        int display = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
        int degrees = display == Surface.ROTATION_90 ? 90 : display == Surface.ROTATION_180 ? 180 : display == Surface.ROTATION_270 ? 270 : 0;
        Matrix transform = new Matrix();
        transform.setValues(TakuPreviewGeometry.contain(texture.getWidth(), texture.getHeight(),
                buffer.getWidth(), buffer.getHeight(), sensorOrientation, degrees, mirrorInView));
        texture.setTransform(transform);
    }

    private int directRotation() {
        int display = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
        int degrees = display == Surface.ROTATION_90 ? 90 : display == Surface.ROTATION_180 ? 180 : display == Surface.ROTATION_270 ? 270 : 0;
        return Math.floorMod(sensorOrientation - degrees, 360);
    }

    private void stopDirectFrames() {
        CameraPreviewFrames previous = frames; frames = null;
        if (previous == null) return;
        framesClosing = true;
        previous.close(() -> { framesClosing = false; finishCloseIfReady(); });
    }

    /** A bridge source JPEG has raw-buffer orientation, unlike TextureView's camera transform. */
    Matrix originalImageMatrix(int viewWidth, int viewHeight, int imageWidth, int imageHeight) {
        Matrix transform = new Matrix();
        if (viewWidth < 1 || viewHeight < 1 || imageWidth < 1 || imageHeight < 1) return transform;
        int display = getDisplay() == null ? Surface.ROTATION_0 : getDisplay().getRotation();
        int degrees = display == Surface.ROTATION_90 ? 90 : display == Surface.ROTATION_180 ? 180 : display == Surface.ROTATION_270 ? 270 : 0;
        int rotation = Math.floorMod(sensorOrientation - degrees, 360);
        boolean turn = rotation % 180 != 0;
        float scale = Math.min(viewWidth / (float) (turn ? imageHeight : imageWidth),
                viewHeight / (float) (turn ? imageWidth : imageHeight));
        transform.postTranslate(-imageWidth / 2f, -imageHeight / 2f);
        transform.postRotate(rotation);
        transform.postScale(-scale, scale); // Exactly one UI mirror for the front camera.
        transform.postTranslate(viewWidth / 2f, viewHeight / 2f);
        return transform;
    }
}
