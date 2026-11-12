package com.hsd.universal.shared;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Geometry of a child display rendered into a HSD window on the head unit. */
public final class DesktopRenderPolicy {
    private DesktopRenderPolicy() {}

    public static final Set<String> DEFAULT_PACKAGES = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList("ru.yandex.yandexmaps", "ru.yandex.yandexnavi")));

    public static final int LONG_EDGE = 3840;
    public static final int SHORT_EDGE = 2160;
    public static final long TILE_PIXEL_BUDGET = 10_368_000L;

    public static boolean fitsBuffer(int width, int height) {
        return Math.min(width, height) > 0 && Math.min(width, height) <= SHORT_EDGE
                && Math.max(width, height) <= LONG_EDGE;
    }

    public static boolean isMap(String pkg) {
        return DEFAULT_PACKAGES.contains(pkg);
    }

    /** Legacy two-map switch on hosts without a package-specific configuration endpoint. */
    public static int[] geometry(String pkg, int width, int height, int parentDpi, boolean compactMapSymbols) {
        return geometry(width, height, parentDpi, compactMapSymbols && isMap(pkg));
    }

    /** Buffer width, buffer height and Android logical density for an already resolved selection. */
    public static int[] geometry(int width, int height, int parentDpi, boolean correctThisPackage) {
        return geometry(width, height, parentDpi, correctThisPackage, 0);
    }

    public static int[] geometry(int width, int height, int parentDpi, boolean correctThisPackage, long parentPixels) {
        if (!fitsBuffer(width, height) || parentDpi < 96 || parentDpi > 640) {
            throw new IllegalArgumentException("Недопустимый размер окна или DPI рабочего стола");
        }
        int w = Math.max(128, width);
        int h = Math.max(128, height);
        if (!correctThisPackage) return new int[]{w, h, parentDpi};

        // A fixed-pixel sign is half size at 160 DPI. A dp button keeps its on-screen size
        // because density and surface dimensions change by the same factor.
        double scale = Math.max(1.0, 320.0 / parentDpi);
        scale = Math.min(scale, Math.min((double) LONG_EDGE / Math.max(w, h), (double) SHORT_EDGE / Math.min(w, h)));
        // On large desktops both tiles share the same proportional pixel allowance.
        if (parentPixels > 1920L * 1080) {
            scale = Math.min(scale, Math.sqrt((double) TILE_PIXEL_BUDGET / parentPixels));
            return new int[]{(int) Math.floor(w * scale), (int) Math.floor(h * scale), (int) Math.round(parentDpi * scale)};
        }
        // At very low DPI allow more than 2x for small tiles only. Two such tiles
        // share the producer's 10,368,000-pixel budget.
        double pixels = (double) w * h;
        if (scale > 2.0) scale = Math.min(scale, Math.max(2.0, Math.sqrt(5_184_000.0 / pixels)));
        return new int[]{(int) Math.round(w * scale), (int) Math.round(h * scale), (int) Math.round(parentDpi * scale)};
    }
}
