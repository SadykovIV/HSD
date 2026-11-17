package com.hsd.universal.shared;

/** Automatic return may fill an empty child, never replace an existing navigation stack. */
public final class EmbeddedTaskRestore {
    private EmbeddedTaskRestore() {}

    public static boolean shouldLaunch(int taskState) {
        // -1: unavailable; 0: absent; 1: present with another activity on top; 2: foreground.
        return taskState == 0;
    }
}
