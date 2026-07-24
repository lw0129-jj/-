package com.lensmind.essay;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 41;
    private static final int GREEN = Color.rgb(98, 255, 192);

    private enum State { IDLE, CAPTURING, REQUESTING, SHOWING, ERROR }

    private FrameLayout root;
    private TextView pageCounter;
    private TextView content;
    private TextView hint;
    private GestureDetector detector;
    private CameraCapture cameraCapture;
    private ApiClient apiClient;
    private final List<String> pages = new ArrayList<String>();
    private int pageIndex;
    private State state = State.IDLE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            configureWindow();
            buildUi();
            configureInput();
            cameraCapture = new CameraCapture(this);
            apiClient = new ApiClient();
            showIdle();
        } catch (Throwable error) {
            showEmergency();
        }
    }

    private void configureWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.rgb(7, 10, 9));
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        pageCounter = makeText(15, Gravity.CENTER);
        FrameLayout.LayoutParams counterParams = new FrameLayout.LayoutParams(dp(96), dp(34), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        counterParams.topMargin = dp(6);
        root.addView(pageCounter, counterParams);

        content = makeText(20, Gravity.CENTER);
        content.setLineSpacing(dp(1), 1.12f);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.setBackground(makeOutline());
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        contentParams.leftMargin = dp(14);
        contentParams.rightMargin = dp(14);
        contentParams.topMargin = dp(46);
        contentParams.bottomMargin = dp(44);
        root.addView(content, contentParams);

        hint = makeText(13, Gravity.CENTER);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(38),
                Gravity.BOTTOM
        );
        hintParams.leftMargin = dp(10);
        hintParams.rightMargin = dp(10);
        hintParams.bottomMargin = dp(2);
        root.addView(hint, hintParams);

        setContentView(root);
        root.requestFocus();
    }

    private TextView makeText(float sizeSp, int gravity) {
        TextView view = new TextView(this);
        view.setTextColor(GREEN);
        view.setTextSize(sizeSp);
        view.setGravity(gravity);
        view.setIncludeFontPadding(false);
        view.setBackgroundColor(Color.TRANSPARENT);
        return view;
    }

    private GradientDrawable makeOutline() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setStroke(dp(2), GREEN);
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private void configureInput() {
        detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                handleSingleClick();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                handleBackAction();
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) { return true; }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) { return true; }
        });
    }

    private void handleSingleClick() {
        if (state == State.CAPTURING || state == State.REQUESTING) return;
        if (state == State.SHOWING) nextPage();
        else startCapture();
    }

    private void handleBackAction() {
        if (state == State.SHOWING || state == State.ERROR) showIdle();
        else if (state != State.CAPTURING && state != State.REQUESTING) finish();
    }

    private void startCapture() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        state = State.CAPTURING;
        pageCounter.setText("");
        content.setGravity(Gravity.CENTER);
        content.setText("正在拍摄题目…");
        hint.setText("请正对题目并保持清晰");
        cameraCapture.capture(new CameraCapture.Callback() {
            @Override public void onCaptured(final byte[] jpeg) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { requestEssay(jpeg); }
                });
            }
            @Override public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { showError(message); }
                });
            }
        });
    }

    private void requestEssay(byte[] jpeg) {
        state = State.REQUESTING;
        content.setGravity(Gravity.CENTER);
        content.setText("正在识别题目…");
        hint.setText("识图后会自动生成完整作文");
        apiClient.createEssay(jpeg, new ApiClient.Callback() {
            @Override public void onProgress(final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        content.setGravity(Gravity.CENTER);
                        content.setText(message);
                    }
                });
            }
            @Override public void onSuccess(final String essay) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        pages.clear();
                        pages.addAll(Paginator.paginate(essay));
                        pageIndex = 0;
                        state = State.SHOWING;
                        renderPage();
                    }
                });
            }
            @Override public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { showError(message); }
                });
            }
        });
    }

    private void renderPage() {
        if (pages.isEmpty()) {
            showError("作文内容为空");
            return;
        }
        pageCounter.setText((pageIndex + 1) + "/" + pages.size());
        content.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        content.setText(pages.get(pageIndex));
        hint.setText(pageIndex == pages.size() - 1 ? "单击回到第一页 · 双击返回" : "单击下一页 · 双击返回");
    }

    private void nextPage() {
        if (pages.isEmpty()) return;
        pageIndex = (pageIndex + 1) % pages.size();
        renderPage();
    }

    private void showIdle() {
        state = State.IDLE;
        pages.clear();
        pageIndex = 0;
        if (pageCounter != null) pageCounter.setText("");
        if (content != null) {
            content.setGravity(Gravity.CENTER);
            content.setText("单击识别作文题目");
        }
        if (hint != null) hint.setText("单击识别 · 作文中单击翻页 · 双击退出");
    }

    private void showError(String message) {
        state = State.ERROR;
        pageCounter.setText("");
        content.setGravity(Gravity.CENTER);
        content.setText(message + "\n\n单击重试");
        hint.setText("双击返回");
    }

    private void showEmergency() {
        TextView emergency = new TextView(this);
        emergency.setBackgroundColor(Color.BLACK);
        emergency.setTextColor(GREEN);
        emergency.setGravity(Gravity.CENTER);
        emergency.setTextSize(18);
        emergency.setText("乐奇作文已启动\n界面初始化失败，请重新打开");
        setContentView(emergency);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (detector != null && detector.onTouchEvent(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_SPACE
                || keyCode == KeyEvent.KEYCODE_CAMERA
                || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            handleSingleClick();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            handleBackAction();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startCapture();
            else showError("需要摄像头权限才能识别题目");
        }
    }

    @Override
    protected void onDestroy() {
        if (cameraCapture != null) cameraCapture.shutdown();
        if (apiClient != null) apiClient.shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
