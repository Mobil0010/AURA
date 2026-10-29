package dev.aura.player;

public final class NativeAudio {
    static {
        System.loadLibrary("aura_audio");
    }

    public static native long createSox(
            int inRate, int outRate, int channels, int quality, double phase, double cutoff);

    /** Interleaved float samples; null drains all pending samples at EOF. */
    public static native long createSox64(
            int inRate, int outRate, int channels, int quality, double phase, double cutoff);

    public static native double[] resample64(long handle, double[] input);

    public static native float[] resample(long handle, float[] input);

    public static native void closeSox(long handle);

    public static native long openUsb(int fd, int rate, int channels, int bits, int bufferMs);

    public static native int writeUsb(long handle, byte[] pcm, int offset, int size);

    public static native long usbFrames(long handle);

    public static native void pauseUsb(long handle, boolean pause);

    public static native String usbInfo(long handle);

    public static native void closeUsb(long handle);
}
