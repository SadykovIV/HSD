package ru.drivedeck.hsd;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import java.util.List;

/** An automatic request acts only on the saved player and never opens an activity. */
public final class ProjectionMusic {
    // All session lifecycle and startup callbacks run on the launcher's main thread.
    private static long sessionId;
    private static long requestedSessionId;

    static void attachSession(long id) { sessionId = id; }

    public static boolean hasPendingStart() { return sessionId > 0 && sessionId != requestedSessionId; }

    public static boolean alreadyRequested(boolean original) {
        return sessionId > 0 ? sessionId == requestedSessionId : original;
    }

    public static boolean claimResume() {
        if (alreadyRequested(false)) return false;
        requestedSessionId = sessionId;
        return true;
    }

    public static void resume(Context context, String player) {
        if (player == null || player.isEmpty()) return;
        MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
        ComponentName listener = new ComponentName(context, "ru.drivedeck.launcher.NotificationBridgeService");
        try {
            for (MediaController controller : manager.getActiveSessions(listener)) {
                if (!player.equals(controller.getPackageName())) continue;
                PlaybackState state = controller.getPlaybackState();
                if (state != null && (state.getState() == PlaybackState.STATE_PLAYING ||
                        state.getState() == PlaybackState.STATE_BUFFERING ||
                        state.getState() == PlaybackState.STATE_CONNECTING)) return;
                controller.getTransportControls().play();
                Log.i("DriveDeckMusic", "Resume via player session: " + player);
                return;
            }
        } catch (SecurityException denied) { /* Some phones grant media buttons only. */ }
        long at = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(at, at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        try {
            if (Build.VERSION.SDK_INT >= 33 && player.equals(manager.getMediaKeyEventSessionPackageName())) {
                AudioManager audio = context.getSystemService(AudioManager.class);
                audio.dispatchMediaKeyEvent(down);
                audio.dispatchMediaKeyEvent(up);
                Log.i("DriveDeckMusic", "Resume via system playback target: " + player);
                return;
            }
        } catch (SecurityException denied) { /* Try the selected player's public receiver. */ }
        Intent button = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(player);
        List<ResolveInfo> receivers = context.getPackageManager().queryBroadcastReceivers(button, 0);
        for (ResolveInfo match : receivers) {
            ActivityInfo info = match.activityInfo;
            if (info == null || !player.equals(info.packageName) || !info.exported || !info.enabled ||
                    (info.permission != null && context.checkSelfPermission(info.permission) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED)) continue;
            button.setComponent(new ComponentName(player, info.name));
            context.sendBroadcast(new Intent(button).putExtra(Intent.EXTRA_KEY_EVENT, down));
            context.sendBroadcast(new Intent(button).putExtra(Intent.EXTRA_KEY_EVENT, up));
            Log.i("DriveDeckMusic", "Resume via player media button: " + player);
            return;
        }
        Log.i("DriveDeckMusic", "Player has no available background playback endpoint: " + player);
    }

    public static void returnToLauncher(Activity activity, Intent request, Dialog music) {
        if (music != null && music.isShowing() && HSDHost.isHostHome(activity, request)) music.dismiss();
    }

    public static boolean canHandleBack(View decor) {
        return decor.hasWindowFocus() || (HSDHost.isHsdProjection() && decor.isShown() &&
                decor.getDisplay() != null && decor.getDisplay().getDisplayId() > 0);
    }

    public static void setMusicContent(Dialog dialog, View music, Activity activity) {
        android.content.SharedPreferences prefs = activity.getSharedPreferences("drive_deck", 0);
        boolean back = ProjectionSettings.navigationOption(prefs, "headswap_fullscreen_back", activity);
        boolean home = ProjectionSettings.navigationOption(prefs, "headswap_fullscreen_home", activity);
        if (!HSDHost.isHsdProjection() || (!back && !home)) { dialog.setContentView(music); return; }
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(music, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.VERTICAL);
        boolean ru = "ru".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
        if (back) addButton(controls, "hsd_music_back", ru ? "Назад" : "Back", dialog::onBackPressed);
        if (home) addButton(controls, "hsd_music_home", ru ? "Домой" : "Home", dialog::dismiss);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        layout.setMarginStart(dp(activity, 12));
        layout.bottomMargin = dp(activity, 12);
        frame.addView(controls, layout);
        dialog.setContentView(frame);
    }

    private static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    private static void addButton(LinearLayout controls, String icon, String label, Runnable action) {
        Context context = controls.getContext();
        ImageButton button = new ImageButton(context);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setImageResource(context.getResources().getIdentifier(icon, "drawable", context.getPackageName()));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(0xd0202934);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x50ffffff), shape, null));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48));
        if (controls.getChildCount() > 0) size.topMargin = dp(context, 8);
        controls.addView(button, size);
    }

    private ProjectionMusic() {}
}
