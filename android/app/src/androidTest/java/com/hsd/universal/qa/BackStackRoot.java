package com.hsd.universal.qa;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

/** Runs standalone in the test APK process. Two ordinary Backs cannot leave this window. */
public final class BackStackRoot extends Activity {
    public static final String ACTION = "com.hsd.universal.test.FULLSCREEN_PAGE";
    private TextView page;
    private int depth = 3;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = new TextView(this);
        setContentView(page);
        page.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
            private boolean reported;
            @Override public void onDraw() {
                if (reported) return;
                reported = true;
                // onDraw runs before the first buffer is submitted. Wait for that
                // commit, then let the probe synchronize WindowManager input state.
                page.getViewTreeObserver().registerFrameCommitCallback(() -> page.post(() -> {
                    if (!isDestroyed()) sendBroadcast(new Intent(ACTION).setPackage("com.hsd.universal")
                            .putExtra("display", getWindowManager().getDefaultDisplay().getDisplayId())
                            .putExtra("ready", true));
                }));
            }
        });
        page.setOnTouchListener((view, event) -> {
            sendBroadcast(new Intent(ACTION).setPackage("com.hsd.universal")
                    .putExtra("display", getWindowManager().getDefaultDisplay().getDisplayId())
                    .putExtra("touch", event.getActionMasked()).putExtra("count", event.getPointerCount())
                    .putExtra("changedId", event.getPointerId(event.getActionIndex())));
            return true;
        });
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::popPage);
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) report();
    }

    @Override public void onBackPressed() { popPage(); }

    private void popPage() {
        depth--;
        report();
    }

    private void report() {
        page.setText(String.valueOf(depth));
        sendBroadcast(new Intent(ACTION).setPackage("com.hsd.universal")
                .putExtra("display", getWindowManager().getDefaultDisplay().getDisplayId())
                .putExtra("depth", depth));
    }
}
