package android.os;

/** Compile-time stub only. */
public interface IBinder {
    boolean transact(int code, Parcel data, Parcel reply, int flags);
    boolean isBinderAlive();
    boolean pingBinder();
    void linkToDeath(DeathRecipient recipient, int flags);
    boolean unlinkToDeath(DeathRecipient recipient, int flags);

    interface DeathRecipient {
        void binderDied();
    }
}
