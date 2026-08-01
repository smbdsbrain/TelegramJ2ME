package tg.app;

import javax.microedition.lcdui.Displayable;

import tg.mem.MemoryBudget;

/**
 * Bounded navigation history with a root that is never discarded.
 *
 * Every retained screen is live: a ChatScreen holds its wrapped transcript and
 * its decoded thumbnails for as long as it is on the stack. The depth is
 * therefore a memory budget, sized from the measured heap.
 */
public final class ScreenStack
{
    private final int capacity;
    private final Displayable[] screens;
    private int size;

    public ScreenStack()
    {
        this(MemoryBudget.screenStackDepth());
    }

    /**
     * @param capacity screens to retain; floored at four, which is root ->
     *                 dialog list -> chat -> photo, the deepest path the client
     *                 has. Below that, Back starts losing the way home.
     */
    public ScreenStack(int capacity)
    {
        if (capacity < 4) { capacity = 4; }
        this.capacity = capacity;
        this.screens = new Displayable[capacity];
    }

    public int capacity() { return capacity; }

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
        if (size == capacity)
        {
            System.arraycopy(screens, 2, screens, 1, capacity - 2);
            screens[capacity - 1] = null;
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
