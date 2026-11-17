package ru.drivedeck.hsd;

import com.hsd.universal.shared.EmbeddedTaskRestore;

public final class EmbeddedTaskRestoreTest {
    public static void main(String[] args) {
        if (!EmbeddedTaskRestore.shouldLaunch(0)) throw new AssertionError("Empty child was not restored");
        for (int state : new int[]{-1, 1, 2, 3}) {
            if (EmbeddedTaskRestore.shouldLaunch(state))
                throw new AssertionError("Unknown placement or existing navigation stack would be replaced: " + state);
        }
    }
}
