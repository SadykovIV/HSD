package com.hsd.universal.dex;
import android.os.Bundle;
/** Private app-UID lifetime owner. It performs no privileged platform calls. */
interface IVdmPoolTokens {
    Bundle claim(IBinder owner, int stoppedTransaction);
    int ownerPid();
    void retire();
}
