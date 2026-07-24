package com.lensmind.essay;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
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
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class CameraCapture {
    interface Callback {
        void onCaptured(byte[] jpeg);
        void onError(String message);
    }

    private final Activity activity;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private SurfaceTexture previewTexture;
    private Surface previewSurface;
    private boolean completed;

    CameraCapture(Activity activity) {
        this.activity = activity;
    }

    void capture(final Callback callback) {
        shutdown();
        completed = false;
        startThread();

        final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            fail(callback, "没有找到摄像头服务");
            return;
        }

        try {
            final String cameraId = chooseCamera(manager);
            if (cameraId == null) {
                fail(callback, "没有找到可用摄像头");
                return;
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size jpegSize = chooseJpegSize(characteristics);
            Size previewSize = choosePreviewSize(characteristics);

            reader = ImageReader.newInstance(
                    jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2
            );
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader imageReader) {
                    if (completed) return;
                    Image image = null;
                    try {
                        image = imageReader.acquireLatestImage();
                        if (image == null) return;
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        completed = true;
                        callback.onCaptured(bytes);
                    } catch (Throwable error) {
                        fail(callback, "读取图片失败");
                    } finally {
                        if (image != null) image.close();
                        shutdown();
                    }
                }
            }, handler);

            // Rokid/YodaOS camera HAL needs a preview stream before still capture.
            // The surface is off-screen, so the UI remains text-only.
            previewTexture = new SurfaceTexture(10);
            previewTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(previewTexture);

            if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                fail(callback, "没有摄像头权限");
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    camera = device;
                    createSession(callback);
                }

                @Override public void onDisconnected(CameraDevice device) {
                    fail(callback, "摄像头已断开");
                }

                @Override public void onError(CameraDevice device, int error) {
                    fail(callback, "摄像头启动失败：" + error);
                }
            }, handler);

            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!completed) fail(callback, "拍摄超时，请重试");
                }
            }, 18000L);
        } catch (Throwable error) {
            fail(callback, "摄像头启动失败：" + simpleMessage(error));
        }
    }

    private void createSession(final Callback callback) {
        if (camera == null || reader == null || previewSurface == null) {
            fail(callback, "摄像头未准备好");
            return;
        }

        try {
            camera.createCaptureSession(
                    Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(final CameraCaptureSession captureSession) {
                            session = captureSession;
                            try {
                                final CaptureRequest.Builder preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                preview.addTarget(previewSurface);
                                preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                preview.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                session.setRepeatingRequest(preview.build(), null, handler);

                                // Give the custom camera HAL a short warm-up before still capture.
                                handler.postDelayed(new Runnable() {
                                    @Override public void run() {
                                        takeStill(callback);
                                    }
                                }, 650L);
                            } catch (Throwable error) {
                                fail(callback, "预览启动失败：" + simpleMessage(error));
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession captureSession) {
                            fail(callback, "摄像头配置失败");
                        }
                    },
                    handler
            );
        } catch (Throwable error) {
            fail(callback, "摄像头配置失败：" + simpleMessage(error));
        }
    }

    private void takeStill(final Callback callback) {
        if (completed || camera == null || session == null || reader == null) return;
        try {
            CaptureRequest.Builder still = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(reader.getSurface());
            still.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            still.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            still.set(CaptureRequest.JPEG_QUALITY, (byte) 82);
            session.capture(still.build(), new CameraCaptureSession.CaptureCallback() {}, handler);
        } catch (Throwable error) {
            fail(callback, "拍摄失败：" + simpleMessage(error));
        }
    }

    private void fail(Callback callback, String message) {
        if (completed) return;
        completed = true;
        callback.onError(message);
        shutdown();
    }

    private static String chooseCamera(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            if (fallback == null) fallback = id;
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return fallback;
    }

    private static Size chooseJpegSize(CameraCharacteristics characteristics) {
        android.hardware.camera2.params.StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(1280, 720);
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        return chooseClosest(sizes, 1920, 1080, 3500000L);
    }

    private static Size choosePreviewSize(CameraCharacteristics characteristics) {
        android.hardware.camera2.params.StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(1280, 720);
        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
        return chooseClosest(sizes, 1280, 720, 2200000L);
    }

    private static Size chooseClosest(Size[] sizes, int targetWidth, int targetHeight, long maxArea) {
        if (sizes == null || sizes.length == 0) return new Size(targetWidth, targetHeight);
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * (long) size.getHeight();
            if (area > maxArea) continue;
            long score = Math.abs(size.getWidth() - targetWidth) + Math.abs(size.getHeight() - targetHeight);
            if (score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        if (best != null) return best;
        return sizes[sizes.length - 1];
    }

    private void startThread() {
        thread = new HandlerThread("leqi-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    void shutdown() {
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        try { if (camera != null) camera.close(); } catch (Throwable ignored) {}
        try { if (reader != null) reader.close(); } catch (Throwable ignored) {}
        try { if (previewSurface != null) previewSurface.release(); } catch (Throwable ignored) {}
        try { if (previewTexture != null) previewTexture.release(); } catch (Throwable ignored) {}
        session = null;
        camera = null;
        reader = null;
        previewSurface = null;
        previewTexture = null;
        if (thread != null) {
            try { thread.quitSafely(); } catch (Throwable ignored) {}
            thread = null;
            handler = null;
        }
    }

    private static String simpleMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().length() == 0) return "未知错误";
        return message.trim();
    }
}
