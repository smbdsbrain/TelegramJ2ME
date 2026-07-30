package tg.api;

/** Cooperative cancellation shared by a photo screen and its worker. */
public final class DownloadToken
{
    public interface ProgressListener
    {
        void onProgress(int downloaded, int expected);
    }

    private volatile boolean cancelled;
    private ProgressListener progressListener;

    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled; }

    public synchronized void setProgressListener(ProgressListener value)
    {
        progressListener = value;
    }

    public void progress(int downloaded, int expected)
    {
        ProgressListener listener;
        synchronized (this) { listener = progressListener; }
        if (listener != null) { listener.onProgress(downloaded, expected); }
    }
}
