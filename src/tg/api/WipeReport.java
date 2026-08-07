package tg.api;

/**
 * What a local account erasure managed to erase.
 *
 * Logging out used to be reported as done the moment the screen changed, while
 * each store's failure went to a log ring the next launch discards. Whether an
 * account is still on the handset is not a debugging detail: it is the whole
 * question the user asked, and it has to reach them.
 *
 * <h3>Labels, never values</h3>
 * {@link #failed} names components - "drafts", "auth keys" - and nothing else.
 * This object is built to be shown on a screen and read out over a support
 * channel, so it may not carry a key, a phone number, a peer title or a message
 * body. {@code tgtest.AccountWipeTest} asserts that for a report in which every
 * component failed.
 */
public final class WipeReport
{
    /** True when every component reported success. */
    public final boolean complete;

    /**
     * Component labels that did not erase, comma separated; empty when clean.
     */
    public final String failed;

    WipeReport(boolean complete, String failed)
    {
        this.complete = complete;
        this.failed = failed == null ? "" : failed;
    }

    /** One line, safe to show and to log. */
    public String describe()
    {
        return complete ? "local account data erased"
                        : "local account data NOT fully erased: " + failed;
    }
}
