package ru.drivedeck.hsd;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;

import com.hsd.universal.shared.DesktopRenderPolicy;

public final class HostedDisplay {
    // Display.FLAG_OWN_FOCUS in Android 14+. DisplayManager uses a different bit when creating it.
    private static final int DISPLAY_OWN_FOCUS = 1 << 11;

    public static void observePresentation(Activity activity, ViewTreeObserver.OnPreDrawListener listener,
                                           boolean attach) {
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (!observer.isAlive()) return;
        if (!attach) {
            observer.removeOnPreDrawListener(listener);
            return;
        }
        Display display = of(activity);
        if (display == null || display.getDisplayId() <= 0) return;
        // The upstream focus callback arms the page on a display with independent input focus.
        if (Build.VERSION.SDK_INT >= 34 && (display.getFlags() & DISPLAY_OWN_FOCUS) != 0) return;
        observer.addOnPreDrawListener(listener);
    }

    public static boolean isPresented(Activity activity) {
        View window = activity.getWindow().getDecorView();
        return window.isShown() && window.getWindowVisibility() == View.VISIBLE && displayId(activity) > 0;
    }

    public static int[] renderGeometry(int width, int height, int density, boolean compact, View renderer) {
        Display display = renderer.getDisplay();
        Display.Mode mode = display == null ? null : display.getMode();
        long parentPixels = mode == null ? 0 : (long) mode.getPhysicalWidth() * mode.getPhysicalHeight();
        return DesktopRenderPolicy.geometry(width, height, density, compact, parentPixels);
    }

    @SuppressWarnings("deprecation")
    public static Display of(Activity activity) {
        return Build.VERSION.SDK_INT >= 30 ? activity.getDisplay() : activity.getWindowManager().getDefaultDisplay();
    }

    public static Activity activity(Context context) {
        for (int i = 0; i < 12 && context != null; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) return null;
            context = base;
        }
        return null;
    }
    public static int displayId(Context context) {
        try {
            Activity activity = activity(context);
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return -1;
            Display display = of(activity);
            return display == null || !display.isValid() || display.getDisplayId() <= 0 ? -1 : display.getDisplayId();
        } catch (RuntimeException e) { return -1; }
    }
    private HostedDisplay() {}
}
