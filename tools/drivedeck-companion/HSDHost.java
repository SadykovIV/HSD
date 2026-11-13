package ru.drivedeck.hsd;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import com.hsd.universal.shared.ProjectionHost;

/** Resolve the host that launched this display. The gateway separately verifies ownership of the parent. */
public final class HSDHost {
    private static final String UNIVERSAL = "com.hsd.universal";
    private static final String CLASSIC = "com.headswap.desk";
    private static final String EARLIER_AA = "com.hsd.aa";

    private static String declared(Intent launch, int display) {
        if (launch == null || display <= 0 || launch.getIntExtra(ProjectionHost.DISPLAY, -1) != display) return null;
        String host = launch.getStringExtra(ProjectionHost.PACKAGE);
        return UNIVERSAL.equals(host) || CLASSIC.equals(host) ? host : null;
    }

    public static void newLaunch(Activity activity, Intent incoming) {
        int display = HostedDisplay.displayId(activity);
        String host = declared(incoming, display);
        if (host != null && activity.getIntent() != null) {
            activity.getIntent().putExtra(ProjectionHost.PACKAGE, host).putExtra(ProjectionHost.DISPLAY, display);
        }
    }

    static String onDisplay(Context context) {
        Activity activity = HostedDisplay.activity(context);
        int display = HostedDisplay.displayId(context);
        if (activity == null || display <= 0) return null;
        String host = declared(activity.getIntent(), display);
        if (host != null) return host;
        // Previously released hosts did not send launch extras. The AA display used its installed app label.
        PackageManager pm = context.getPackageManager();
        try {
            String label = pm.getApplicationLabel(pm.getApplicationInfo(EARLIER_AA, 0)).toString();
            if ((label + " DriveDeck").equals(HostedDisplay.of(activity).getName())) return EARLIER_AA;
        } catch (PackageManager.NameNotFoundException ignored) { }
        return CLASSIC;
    }

    // The author-supplied renderer keeps one Binder per process. HSD has a separate
    // entry activity/process so opening Desktop alongside it cannot redirect an active lease.
    private static final String UNIVERSAL_PROCESS = "ru.drivedeck.headswap:hsd";
    private static volatile String processHost;

    public static void attach(Activity activity, Intent launch) {
        String declared = declared(launch, HostedDisplay.displayId(activity));
        boolean universal = android.os.Build.VERSION.SDK_INT >= 28 &&
            UNIVERSAL_PROCESS.equals(android.app.Application.getProcessName());
        String host = universal ? UNIVERSAL : (declared == null ? onDisplay(activity) : declared);
        if (universal && declared != null && !UNIVERSAL.equals(declared)) throw new SecurityException("Unexpected desktop host");
        if (host != null) processHost = host;
    }

    public static ComponentName rendererGateway(Context context) {
        String host = processHost;
        return host == null ? null : gateway(context, host);
    }

    public static ComponentName rendererFullscreen(Context context) {
        String host = processHost;
        return host == null ? null : endpoint(context, host, false);
    }

    public static String updateNotice(Context context) {
        java.util.Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return "ru".equals(locale.getLanguage()) ? "Для обновления откройте раздел «Компоненты» в HSD."
                                                 : "Open Components in HSD to update DriveDeck.";
    }

    static ComponentName fullscreen(Context context) {
        return endpoint(context, onDisplay(context), false);
    }

    static ComponentName gateway(Context context, String host) {
        return endpoint(context, host, true);
    }

    private static ComponentName endpoint(Context context, String host, boolean service) {
        if (!UNIVERSAL.equals(host) && !CLASSIC.equals(host) && !EARLIER_AA.equals(host)) return null;
        PackageManager pm = context.getPackageManager();
        if (pm.checkSignatures(context.getPackageName(), host) != PackageManager.SIGNATURE_MATCH) return null;
        String suffix = service ? ".embed.EmbeddedDisplayService" : ".desktop.DriveDeckFullscreenActivity";
        for (String namespace : UNIVERSAL.equals(host) ? new String[]{UNIVERSAL} : new String[]{UNIVERSAL, CLASSIC}) {
            ComponentName name = new ComponentName(host, namespace + suffix);
            try {
                ComponentInfo info = service ? pm.getServiceInfo(name, 0) : pm.getActivityInfo(name, 0);
                String permission = service ? ((ServiceInfo) info).permission : ((ActivityInfo) info).permission;
                if (info.exported && info.enabled && info.applicationInfo.enabled &&
                        (host + ".permission.EMBED_DISPLAY").equals(permission)) return name;
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return null;
    }

    private HSDHost() {}
}
