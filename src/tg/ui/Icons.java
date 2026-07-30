package tg.ui;

import javax.microedition.lcdui.Graphics;

import tg.api.Peer;

/** Tiny geometric icons; no bitmap resources or protected branding. */
public final class Icons
{
    private Icons() { }

    public static void peer(Graphics g, Peer peer, int x, int y, int size,
                            int colour)
    {
        g.setColor(colour);
        if (peer != null && peer.self)
        {
            int mid = size / 2;
            g.drawRect(x + size / 5, y + size / 5,
                    size * 3 / 5, size * 3 / 5);
            g.drawLine(x + size / 5, y + mid, x + mid, y + size * 4 / 5);
            g.drawLine(x + mid, y + size * 4 / 5,
                    x + size * 4 / 5, y + mid);
            return;
        }
        int kind = peer == null ? Peer.USER : peer.kind;
        if (kind == Peer.CHANNEL)
        {
            g.drawLine(x + size / 5, y + size / 3,
                    x + size * 4 / 5, y + size / 5);
            g.drawLine(x + size / 5, y + size * 2 / 3,
                    x + size * 4 / 5, y + size * 4 / 5);
            g.drawLine(x + size / 5, y + size / 3,
                    x + size / 5, y + size * 2 / 3);
            g.drawLine(x + size * 4 / 5, y + size / 5,
                    x + size * 4 / 5, y + size * 4 / 5);
            return;
        }
        if (kind == Peer.CHAT)
        {
            int r = Math.max(2, size / 5);
            g.fillArc(x + size / 8, y + size / 8, r, r, 0, 360);
            g.fillArc(x + size / 2, y + size / 8, r, r, 0, 360);
            g.drawArc(x + size / 10, y + size / 2,
                    size * 4 / 5, size / 3, 0, 180);
            return;
        }
        int head = Math.max(2, size / 3);
        g.fillArc(x + (size - head) / 2, y + size / 8,
                head, head, 0, 360);
        g.drawArc(x + size / 6, y + size / 2,
                size * 2 / 3, size / 3, 0, 180);
    }

    public static void pin(Graphics g, int x, int y, int size, int colour)
    {
        g.setColor(colour);
        int half = Math.max(1, size / 2);
        g.drawLine(x, y, x + half, y + half);
        g.drawLine(x + half, y, x, y + half);
        g.drawLine(x + half / 2, y + half / 2,
                x + half / 2, y + size);
    }
}
