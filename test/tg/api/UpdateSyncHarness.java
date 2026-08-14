package tg.api;

/** Test-only access to UpdateSync's package-private timing seam. */
public final class UpdateSyncHarness
{
    private UpdateSyncHarness() { }

    public static UpdateSync create(UpdateSync.Invoker invoker, PeerCache peers,
                                    long auditMs, int[] retryMs)
    {
        return new UpdateSync(invoker, peers, auditMs, retryMs);
    }
}
