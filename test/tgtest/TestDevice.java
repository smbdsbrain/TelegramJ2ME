package tgtest;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import javax.microedition.lcdui.Image;

import org.microemu.DisplayComponent;
import org.microemu.EmulatorContext;
import org.microemu.app.ui.noui.NoUiDisplayComponent;
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
 * Minimal MicroEmulator device for desktop tests.
 *
 * LCDUI constructors reach through {@code DeviceFactory.getDevice()} for a
 * {@link UIFactory}, so a screen cannot even be built without one installed.
 * This supplies the smallest device that satisfies that, backed by the real
 * J2SE font manager and display so text measurement and painting behave the
 * way the emulator does.
 *
 * {@link #getInputMethod()} is null: nothing here delivers key events. Tests
 * drive screens through their own API, or through
 * {@code org.microemu.DisplayAccess} when a MIDlet is running.
 */
public final class TestDevice implements Device
{
    private final J2SEFontManager fonts = new J2SEFontManager();
    private final UIFactory ui = new J2SEDevice().getUIFactory();
    private final NoUiDisplayComponent component = new NoUiDisplayComponent();
    private final J2SEDeviceDisplay screen;
    private final String name;

    public TestDevice(String name, int width, int height)
    {
        this.name = name;
        fonts.init();
        // A context is only optional while nothing repaints. Once a MIDlet is
        // running, MicroEmulator's event thread services paint events and dies
        // on the first one if the display cannot reach a component - and a dead
        // event thread means setCurrent silently stops working.
        screen = new J2SEDeviceDisplay(new Context());
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

    /** Just enough emulator for a display that paints into nothing. */
    private final class Context implements EmulatorContext
    {
        public DisplayComponent getDisplayComponent() { return component; }
        public InputMethod getDeviceInputMethod() { return null; }
        public DeviceDisplay getDeviceDisplay() { return screen; }
        public FontManager getDeviceFontManager() { return fonts; }
        public boolean platformRequest(String url) { return false; }

        public InputStream getResourceAsStream(String resource)
        {
            return TestDevice.class.getResourceAsStream(resource);
        }
    }
}
