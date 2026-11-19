package com.hsd.universal.hicar;

/** JNI calls are serialized: the underlying HUKS storage is process-global. */
final class HiCarNativeAuth {
    static { System.loadLibrary("hsd_hicar_auth"); }
    static synchronized native int init(String directory, String seed);
    static synchronized native long create(String self, String peer, String pin, int role);
    static synchronized native boolean trusted(long handle);
    static synchronized native byte[][] step(long handle, byte[] message, int keyLength);
    static synchronized native byte[] sessionKey(long handle);
    static synchronized native void close(long handle);
    private HiCarNativeAuth() {}
}
