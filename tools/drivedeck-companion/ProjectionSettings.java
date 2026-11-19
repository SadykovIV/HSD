package ru.drivedeck.hsd;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import com.hsd.universal.shared.ProjectionHost;

/** Preferences shared with the author's fullscreen launcher and older HSD companions. */
public final class ProjectionSettings {
    private static final String JOIN_PANES = "hsd_join_panes";
    private static final String BACK = "headswap_fullscreen_back";
    private static final String MENU = "hsd_fullscreen_menu";
    private static SharedPreferences layoutPreferences;

    private ProjectionSettings() {}

    public static void initializeLayout(Context app) {
        layoutPreferences = app.getSharedPreferences("drive_deck", 0);
    }

    /** DriveDeck applies its own screen scale to this value when laying out each page. */
    public static float paneGap() {
        return gridGap(12f);
    }

    public static float gridGap(float originalPixels) {
        return layoutPreferences.getBoolean(JOIN_PANES, false) ? 0f : originalPixels;
    }

    /** The settings dialog and the desktop have separate view roots. */
    public static void observePaneSpacing(View page) {
        SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
            if (key == null || JOIN_PANES.equals(key)) page.requestLayout();
        };
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                layoutPreferences.registerOnSharedPreferenceChangeListener(listener);
            }
            @Override public void onViewDetachedFromWindow(View view) {
                layoutPreferences.unregisterOnSharedPreferenceChangeListener(listener);
            }
        });
    }

    public static void addPaneSpacing(LinearLayout card, Switch example, String language) {
        boolean ru = "ru".equals(language);
        addSwitch(card, example, JOIN_PANES, ru ? "Окна без промежутка" : "Remove gap between windows");
    }

    public static void addNavigation(LinearLayout card, Switch example, String language) {
        boolean ru = "ru".equals(language);
        addSwitch(card, example, BACK,
                ru ? "Назад в полноэкранных приложениях" : "Back in fullscreen apps");
        if (HSDHost.supportsAppMenu(card.getContext())) {
            addSwitch(card, example, MENU, ru ? "Меню приложений в полном экране" : "App menu in fullscreen apps");
        }
        addSwitch(card, example, "headswap_fullscreen_home",
                ru ? "Домой в полноэкранных приложениях" : "Home in fullscreen apps");
        TextView hint = new TextView(card.getContext());
        hint.setTextColor(example.getTextColors());
        hint.setText(ru ? "Два быстрых нажатия «Назад» возвращают в DriveDeck. Нижние полосы можно скрыть в разделе «Главный экран» → «Полосы окон»."
                : "Press Back twice to return to DriveDeck. To hide the bottom bars, open Home screen → Window bars.");
        card.addView(hint);
    }

    /** Respect explicit choices from earlier releases. New HSD desktops start with Back and Menu. */
    public static boolean navigationOption(SharedPreferences prefs, String key, Context context) {
        if (MENU.equals(key)) return HSDHost.supportsAppMenu(context) && prefs.getBoolean(MENU, true);
        if (prefs.contains(key)) return prefs.getBoolean(key, false);
        return BACK.equals(key) && HSDHost.supportsAppMenu(context);
    }

    public static boolean needsControls(boolean backOrHome, Context context) {
        return backOrHome || navigationOption(layoutPreferences, MENU, context) || HSDHost.supportsCarMenu(context);
    }

    public static void addMenuRequest(Context context, Intent request) {
        Activity activity = HostedDisplay.activity(context);
        if (activity != null && navigationOption(layoutPreferences, MENU, context)) {
            request.putExtra(ProjectionHost.APP_MENU, true);
            request.putExtra(ProjectionHost.LAUNCHER_CLASS, activity.getComponentName().getClassName());
        }
    }

    private static void addSwitch(LinearLayout card, Switch example, String key, String text) {
        Switch control = copySwitch(card, example, text);
        SharedPreferences prefs = card.getContext().getSharedPreferences("drive_deck", 0);
        control.setChecked(navigationOption(prefs, key, card.getContext()));
        control.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
    }

    private static Switch copySwitch(LinearLayout card, Switch example, String text) {
        Switch control = new Switch(card.getContext());
        control.setText(text);
        control.setTextSize(TypedValue.COMPLEX_UNIT_PX, example.getTextSize());
        control.setTextColor(example.getTextColors());
        control.setThumbTintList(example.getThumbTintList());
        control.setTrackTintList(example.getTrackTintList());
        control.setMinHeight(Math.round(48 * card.getResources().getDisplayMetrics().density));
        card.addView(control);
        return control;
    }
}
