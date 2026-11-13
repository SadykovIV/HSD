package com.hsd.universal.qa;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.IOException;

/** Standalone test-APK service. Only HSD and a documentation prefix enter the emulator tunnel. */
public final class ProjectionTestVpn extends VpnService {
    private ParcelFileDescriptor tunnel;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!"ranchu".equals(Build.HARDWARE) && !"goldfish".equals(Build.HARDWARE))
            throw new IllegalStateException("Disposable emulator required");
        closeTunnel();
        if (VpnService.prepare(this) != null) throw new IllegalStateException("Approve the test VPN first");
        try {
            tunnel = new Builder().setSession("HSD emulator network policy")
                .addAllowedApplication("com.hsd.universal")
                .addAddress("192.0.2.1", 32).addRoute("198.51.100.0", 24).establish();
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException(error);
        }
        if (tunnel == null) throw new IllegalStateException("Android did not establish the test VPN");
        return START_NOT_STICKY;
    }

    private void closeTunnel() {
        if (tunnel != null) {
            try { tunnel.close(); } catch (IOException ignored) { }
            tunnel = null;
        }
    }

    @Override public void onDestroy() {
        closeTunnel();
        super.onDestroy();
    }
}
