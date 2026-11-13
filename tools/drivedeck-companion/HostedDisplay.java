package ru.drivedeck.hsd;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.Display;

public final class HostedDisplay {
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
