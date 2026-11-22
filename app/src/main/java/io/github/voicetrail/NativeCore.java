package io.github.voicetrail;

/** JNI entry points. The Android tool layer requires a positive decision before acting. */
public final class NativeCore {
    static { System.loadLibrary("voicetrail"); }
    private NativeCore() {}
    public static native String start(String goalKind);
    public static native int dispatch(String id, String tool);
    public static native boolean event(String id, String eventKind);
    public static native void close(String id);
}

