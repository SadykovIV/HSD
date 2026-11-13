package com.hsd.universal.carplay;

/** Short-lived Shizuku service. It can only read the local Bluetooth controller identity. */
interface ILocalBluetoothAddress {
    @nullable String readAddress() = 0;
    void destroy() = 16777114;
}
