package tg.app;

import javax.microedition.lcdui.Display;

/**
 * {@link UiDispatcher} on MIDP: {@code Display.callSerially}.
 *
 * callSerially is the only handle a MIDlet has on the display thread. It queues
 * the runnable behind whatever repaint and event delivery is already pending and
 * runs it there, which is precisely the ownership this client wants - and the
 * reason nothing here needs a queue of its own.
 *
 * Built with a live {@code Display}, which a MIDlet only has inside
 * {@code startApp}. That is why {@link TgMidlet} constructs its workers there
 * rather than in a field initialiser.
 */
public final class DisplayDispatcher implements UiDispatcher
{
    private final Display display;

    public DisplayDispatcher(Display display)
    {
        if (display == null) { throw new IllegalArgumentException("display"); }
        this.display = display;
    }

    public void post(Runnable work)
    {
        display.callSerially(work);
    }
}
