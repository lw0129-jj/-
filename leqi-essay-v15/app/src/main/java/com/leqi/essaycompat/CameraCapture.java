package com.leqi.essaycompat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
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

import java.nio.ByteBuffer;
import java.util.Collections;

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
            Size size = chooseSize(characteristics);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
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
            }, 15000L);
        } catch (Throwable error) {
            fail(callback, "摄像头启动失败");
        }
    }

    private void createSession(final Callback callback) {
        if (camera == null || reader == null) {
            fail(callback, "摄像头未准备好");
            return;
        }
        try {
            camera.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession captureSession) {
                    session = captureSession;
                    try {
                        CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        request.addTarget(reader.getSurface());
                        request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        request.set(CaptureRequest.JPEG_QUALITY, (byte) 90);
                        session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {}, handler);
                    } catch (Throwable error) {
                        fail(callback, "拍摄失败");
                    }
                }
                @Override public void onConfigureFailed(CameraCaptureSession captureSession) {
                    fail(callback, "摄像头配置失败");
                }
            }, handler);
        } catch (Throwable error) {
            fail(callback, "摄像头配置失败");
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

    private static Size chooseSize(CameraCharacteristics characteristics) {
        android.hardware.camera2.params.StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(1280, 720);
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Size best = sizes[0];
        long bestArea = 0L;
        for (Size size : sizes) {
            long area = (long) size.getWidth() * (long) size.getHeight();
            if (area <= 2500000L && area > bestArea) {
                best = size;
                bestArea = area;
            }
        }
        return best;
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
        session = null;
        camera = null;
        reader = null;
        if (thread != null) {
            try { thread.quitSafely(); } catch (Throwable ignored) {}
            thread = null;
            handler = null;
        }
    }
}
