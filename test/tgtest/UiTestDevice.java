package tgtest;

import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import javax.microedition.lcdui.Image;

import org.microemu.device.Device;
import org.microemu.device.DeviceDisplay;
import org.microemu.device.FontManager;
import org.microemu.device.InputMethod;
import org.microemu.device.impl.Rectangle;
import org.microemu.device.j2se.J2SEDevice;
import org.microemu.device.j2se.J2SEDeviceDisplay;
import org.microemu.device.j2se.J2SEFontManager;
import org.microemu.device.ui.UIFactory;

/**
 * A screen with working font metrics and nothing else.
 *
 * Every layout test needs one, because {@code Font.stringWidth} goes through
 * {@code DeviceFactory} and returns nonsense - or throws - without a device
 * installed. It has no input method on purpose: showing a TextBox on a device
 * that has none kills MicroEmulator's event thread, and nothing here needs to
 * type.
 */
public final class UiTestDevice implements Device
{
    private final J2SEFontManager fonts = new J2SEFontManager();
    private final UIFactory ui = new J2SEDevice().getUIFactory();
    private final J2SEDeviceDisplay screen = new J2SEDeviceDisplay(null);
    private final String name;

    public UiTestDevice(int width, int height)
    {
        this("ui-test", width, height);
    }

    public UiTestDevice(String name, int width, int height)
    {
        this.name = name;
        fonts.init();
        screen.setDisplayRectangle(new Rectangle(0, 0, width, height));
        screen.setDisplayPaintable(new Rectangle(0, 0, width, height));
        screen.setIsColor(true);
        screen.setNumColors(16777216);
        screen.setNumAlphaLevels(256);
        screen.setBackgroundColor(new org.microemu.device.impl.Color(0xffffff));
        screen.setForegroundColor(new org.microemu.device.impl.Color(0x000000));
    }

    public void init() { }
    public void destroy() { }
    public String getName() { return name; }
    public InputMethod getInputMethod() { return null; }
    public FontManager getFontManager() { return fonts; }
    public DeviceDisplay getDeviceDisplay() { return screen; }
    public UIFactory getUIFactory() { return ui; }
    public Image getNormalImage() { return null; }
    public Image getOverImage() { return null; }
    public Image getPressedImage() { return null; }
    public Vector getSoftButtons() { return new Vector(); }
    public Vector getButtons() { return new Vector(); }
    public boolean hasPointerEvents() { return false; }
    public boolean hasPointerMotionEvents() { return false; }
    public boolean hasRepeatEvents() { return true; }
    public boolean vibrate(int duration) { return false; }
    public Map getSystemProperties() { return new HashMap(); }
}
