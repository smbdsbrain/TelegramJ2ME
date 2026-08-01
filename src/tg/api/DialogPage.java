package tg.api;

/**
 * One reply to {@code messages.getDialogs}, with the two facts the bare array
 * threw away.
 *
 * Paging the chat list needs to know when to stop asking, and the reply already
 * says so: {@code messages.dialogs} is the complete list by definition, and
 * {@code messages.dialogsSlice} carries the server's total. Returning only the
 * dialogs meant discovering the end by spending a round trip on an empty page,
 * and meant the header counting the list against itself - "200/200" on an
 * account with a thousand chats.
 */
public final class DialogPage
{
    public static final DialogPage EMPTY = new DialogPage();

    public Dialog[] dialogs = new Dialog[0];

    /**
     * Dialogs the server says exist, or 0 when it did not say.
     *
     * Advisory. It is the count for the whole list rather than for the folder
     * being paged, and it can move between requests, so it belongs in a header
     * and in a "stop asking" heuristic - not in an allocation.
     */
    public int total;

    /** The reply was {@code messages.dialogs}: there is nothing after it. */
    public boolean complete;

    /** The reply was {@code messages.dialogsNotModified}: the hash matched. */
    public boolean notModified;

    public int size() { return dialogs == null ? 0 : dialogs.length; }
}
