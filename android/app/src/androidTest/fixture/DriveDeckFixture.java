package com.hsd.universal;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

/** Select two installed apps in a disposable emulator's real DriveDeck preferences. */
public final class DriveDeckFixture extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish")) {
            finish(Activity.RESULT_CANCELED, new Bundle());
            return;
        }
        SharedPreferences.Editor prefs = getTargetContext().getSharedPreferences("drive_deck", 0).edit();
        for (String key : new String[]{"home_app", "slot_0", "startup_app_0"})
            prefs.putString(key, "com.android.settings");
        for (String key : new String[]{"music_app_package", "slot_1", "startup_app_1"})
            prefs.putString(key, "com.google.android.deskclock");
        prefs.putString("startup_screen", "split").putBoolean("split_page_enabled", true)
            .putBoolean("hsd_fullscreen_back", true).putBoolean("hsd_fullscreen_home", true);
        finish(prefs.commit() ? Activity.RESULT_OK : Activity.RESULT_CANCELED, new Bundle());
    }
}
