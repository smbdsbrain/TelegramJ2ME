package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/** Bounded adaptive photo viewer with keyboard scrolling and progress text. */
public class PhotoScreen extends Canvas
{
    private final Font font = Font.getFont(
            Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Metrics metrics = new Metrics();
    private Theme theme;
    private Image image;
    private Image fitWidthImage;
    private Image fitScreenImage;
    private String status = "loading...";
    private int offsetX;
    private int offsetY;
    private int zoomMode;
    private int[] zoomSource = new int[0];
    private int[] zoomViewport = new int[0];

    public PhotoScreen()
    {
        this(Theme.byId(Theme.LIGHT));
    }

    public PhotoScreen(Theme theme)
    {
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
    }

    public void setTheme(Theme value)
    {
        theme = value == null ? Theme.byId(Theme.LIGHT) : value;
        repaint();
    }

    public void setStatus(String value)
    {
        status = value == null ? "" : value;
        repaint();
    }

    public void setImage(Image value)
    {
        updateMetrics();
        fitWidthImage = value;
        fitScreenImage = value;
        if (value != null)
        {
            try
            {
                fitScreenImage = ImageScaler.fitBox(value,
                        viewportWidth(), viewportHeight());
            }
            catch (Throwable ignored) { fitScreenImage = value; }
        }
        zoomMode = 0;
        image = fitScreenImage;
        offsetX = 0;
        offsetY = 0;
        status = "";
        repaint();
    }

    public Image image() { return image; }
    public int viewportWidth() { updateMetrics(); return metrics.width; }
    public int viewportHeight()
    {
        updateMetrics();
        return metrics.height;
    }

    /** Cycle whole-photo, fit-width and a real fixed 5x view. */
    public void nextZoom()
    {
        if (fitWidthImage == null) { return; }
        zoomMode = (zoomMode + 1) % 3;
        image = zoomMode == 0 ? fitScreenImage : fitWidthImage;
        offsetX = 0;
        offsetY = 0;
        clamp();
        repaint();
    }

    public boolean isZoomed() { return zoomMode != 0; }
    public int zoomMode() { return zoomMode; }

    protected void paint(Graphics g)
    {
        updateMetrics();
        int width = metrics.width;
        int height = viewportHeight();
        g.setColor(theme.photoBackground);
        g.fillRect(0, 0, width, metrics.height);
        if (image != null)
        {
            if (zoomMode == 2) { drawFiveX(g, width, height); }
            else
            {
                int x = image.getWidth() <= width
                        ? (width - image.getWidth()) / 2 : -offsetX;
                int y = image.getHeight() <= height
                        ? (height - image.getHeight()) / 2 : -offsetY;
                g.drawImage(image, x, y, Graphics.TOP | Graphics.LEFT);
            }

            String mode = zoomMode == 0 ? "Fit screen"
                    : (zoomMode == 1 ? "Fit width" : "5x");
            g.setFont(font);
            int modeWidth = font.stringWidth(mode) + metrics.padding * 2;
            g.setColor(theme.surface);
            g.fillRect(width - modeWidth, 0, modeWidth,
                    font.getHeight() + metrics.padding * 2);
            g.setColor(theme.text);
            g.drawString(mode, width - metrics.padding, metrics.padding,
                    Graphics.TOP | Graphics.RIGHT);
        }
        if (status.length() > 0)
        {
            g.setFont(font);
            int box = font.getHeight() + metrics.padding * 2;
            int y = height - box;
            g.setColor(theme.surface);
            g.fillRect(0, y, width, box);
            g.setColor(theme.text);
            g.drawString(UiChrome.clip(status, font,
                    width - metrics.padding * 2), metrics.padding,
                    y + metrics.padding, Graphics.TOP | Graphics.LEFT);
        }
    }

    protected void sizeChanged(int width, int height)
    {
        metrics.update(width, height, font, font);
        if (fitWidthImage != null)
        {
            try
            {
                fitScreenImage = ImageScaler.fitBox(fitWidthImage,
                        viewportWidth(), viewportHeight());
                if (zoomMode == 0) { image = fitScreenImage; }
            }
            catch (Throwable ignored) { }
        }
        clamp();
    }

    protected void keyPressed(int keyCode)
    {
        if (image == null) { return; }
        int action = 0;
        try { action = getGameAction(keyCode); }
        catch (Throwable ignored) { }
        if (action == FIRE || keyCode == Canvas.KEY_NUM5)
        {
            nextZoom();
            return;
        }
        int step = metrics.panStep;
        if (action == UP) { offsetY -= step; }
        else if (action == DOWN) { offsetY += step; }
        else if (action == LEFT) { offsetX -= step; }
        else if (action == RIGHT) { offsetX += step; }
        clamp();
        repaint();
    }

    protected void keyRepeated(int keyCode) { keyPressed(keyCode); }

    private void clamp()
    {
        if (image == null) { offsetX = offsetY = 0; return; }
        int scale = zoomMode == 2 ? 5 : 1;
        int maxX = Math.max(0, image.getWidth() * scale - viewportWidth());
        int maxY = Math.max(0, image.getHeight() * scale - viewportHeight());
        offsetX = Math.max(0, Math.min(maxX, offsetX));
        offsetY = Math.max(0, Math.min(maxY, offsetY));
    }

    /** Render only the visible 5x viewport; never allocate the virtual image. */
    private void drawFiveX(Graphics g, int width, int height)
    {
        Image source = fitWidthImage;
        int virtualWidth = source.getWidth() * 5;
        int virtualHeight = source.getHeight() * 5;
        int drawWidth = Math.min(width, virtualWidth);
        int drawHeight = Math.min(height, virtualHeight);
        int screenX = virtualWidth < width ? (width - virtualWidth) / 2 : 0;
        int screenY = virtualHeight < height ? (height - virtualHeight) / 2 : 0;

        int sourceX = offsetX / 5;
        int sourceY = offsetY / 5;
        int sourceRight = Math.min(source.getWidth() - 1,
                (offsetX + drawWidth - 1) / 5);
        int sourceBottom = Math.min(source.getHeight() - 1,
                (offsetY + drawHeight - 1) / 5);
        int sourceWidth = sourceRight - sourceX + 1;
        int sourceHeight = sourceBottom - sourceY + 1;
        int sourcePixels = sourceWidth * sourceHeight;
        int viewportPixels = drawWidth * drawHeight;
        if (zoomSource.length < sourcePixels) { zoomSource = new int[sourcePixels]; }
        if (zoomViewport.length < viewportPixels)
        {
            zoomViewport = new int[viewportPixels];
        }
        source.getRGB(zoomSource, 0, sourceWidth,
                sourceX, sourceY, sourceWidth, sourceHeight);
        for (int y = 0; y < drawHeight; y++)
        {
            int sy = (offsetY + y) / 5 - sourceY;
            int sourceRow = sy * sourceWidth;
            int targetRow = y * drawWidth;
            for (int x = 0; x < drawWidth; x++)
            {
                int sx = (offsetX + x) / 5 - sourceX;
                zoomViewport[targetRow + x] = zoomSource[sourceRow + sx];
            }
        }
        g.drawRGB(zoomViewport, 0, drawWidth, screenX, screenY,
                drawWidth, drawHeight, false);
    }

    private void updateMetrics()
    {
        metrics.update(getWidth(), getHeight(), font, font);
    }
}
