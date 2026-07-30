package tg.app;

import javax.microedition.lcdui.Displayable;

/** Bounded navigation history with a root that is never discarded. */
public final class ScreenStack
{
    public static final int CAPACITY = 16;

    private final Displayable[] screens = new Displayable[CAPACITY];
    private int size;

    public void resetRoot(Displayable screen)
    {
        for (int i = 0; i < size; i++) { screens[i] = null; }
        size = 0;
        if (screen != null) { screens[size++] = screen; }
    }

    public void push(Displayable screen)
    {
        if (screen == null) { return; }
        if (size > 0 && screens[size - 1] == screen) { return; }
        if (size == CAPACITY)
        {
            System.arraycopy(screens, 2, screens, 1, CAPACITY - 2);
            screens[CAPACITY - 1] = null;
            size--;
        }
        screens[size++] = screen;
    }

    public void replace(Displayable screen)
    {
        if (screen == null) { return; }
        if (size == 0) { screens[size++] = screen; }
        else { screens[size - 1] = screen; }
    }

    public Displayable pop()
    {
        if (size <= 1) { return current(); }
        screens[--size] = null;
        return screens[size - 1];
    }

    public Displayable current()
    {
        return size == 0 ? null : screens[size - 1];
    }

    public Displayable root()
    {
        return size == 0 ? null : screens[0];
    }

    public boolean isRoot()
    {
        return size <= 1;
    }

    public int depth()
    {
        return size;
    }

    public Displayable at(int index)
    {
        return index >= 0 && index < size ? screens[index] : null;
    }
}
