package tg.app;

/**
 * The one thread allowed to change what the user is looking at.
 *
 * <h3>The rule</h3>
 * Application model, navigation stack and lcdui mutations happen on the display
 * thread, and so do ordinary {@link Worker#submit} calls. Blocking I/O does not.
 * A producer that is on neither thread - a connection listener, the outbox
 * drain, the update queue, a decoder - posts through here first.
 *
 * <h3>Why an interface</h3>
 * lcdui is documented as thread safe for mutating a {@code Displayable}, and
 * that is true and beside the point. Safety per method call says nothing about
 * a <em>transition</em>: "replace the dialog array, rebuild the list, then swap
 * the screen" is three calls, and a second transition landing between any two of
 * them leaves the client showing one account's dialogs under another account's
 * title. What removes that is a single owning thread, not a lock per widget.
 *
 * The interface exists so the contract can be tested. The MIDP implementation
 * is {@link DisplayDispatcher}; the desktop suite substitutes a queue it drains
 * by hand, which is the only way to assert an ordering that on a device is
 * decided by the AMS.
 *
 * Deliberately not an executor. CLDC has no {@code java.util.concurrent}, and a
 * queue of our own would be a second scheduler competing with the one the
 * platform already runs - with its own unbounded backlog to get wrong.
 */
public interface UiDispatcher
{
    /**
     * Run {@code work} on the display thread, later.
     *
     * Never runs it inline, even when called from the display thread: a caller
     * that could be re-entered mid-transition is exactly what this exists to
     * prevent. Ordering is first-in-first-out.
     */
    void post(Runnable work);
}
