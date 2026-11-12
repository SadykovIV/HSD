package com.hsd.universal;

import com.hsd.universal.dex.ManualAccessLauncher;

/** Shell entry point in the HSD installation. */
public final class Activate {
    private Activate() {}

    public static void main(String[] args) {
        ManualAccessLauncher.main(args);
    }
}
