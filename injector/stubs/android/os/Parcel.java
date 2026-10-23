package android.os;

/**
 * Compile-time stub only. At runtime the real android.os.Parcel is used.
 * Do NOT package this class into the injected dex (it is passed to d8 via --lib).
 */
public final class Parcel {
    public static Parcel obtain() { return null; }
    public void recycle() {}
    public void setDataPosition(int pos) {}
    public int dataPosition() { return 0; }

    public void writeInterfaceToken(String interfaceName) {}
    public void writeInt(int val) {}
    public void writeLong(long val) {}
    public void writeFloat(float val) {}
    public void writeDouble(double val) {}
    public void writeString(String val) {}
    public void writeIntArray(int[] val) {}
    public void writeFloatArray(float[] val) {}
    public void writeBlob(byte[] b) {}
    public void writeNoException() {}

    public int readInt() { return 0; }
    public long readLong() { return 0L; }
    public float readFloat() { return 0f; }
    public double readDouble() { return 0d; }
    public String readString() { return null; }
    public int[] createIntArray() { return null; }
    public void readIntArray(int[] val) {}
    public float[] createFloatArray() { return null; }
    public void readFloatArray(float[] val) {}
    public void readException() {}
}
