package tgtest;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.Vector;

import javax.imageio.ImageIO;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import org.microemu.device.Device;
import org.microemu.device.DeviceDisplay;
import org.microemu.device.DeviceFactory;
import org.microemu.device.FontManager;
import org.microemu.device.InputMethod;
import org.microemu.device.impl.Rectangle;
import org.microemu.device.j2se.J2SEDevice;
import org.microemu.device.j2se.J2SEDeviceDisplay;
import org.microemu.device.j2se.J2SEFontManager;
import org.microemu.device.j2se.J2SEMutableImage;
import org.microemu.device.ui.UIFactory;

import tg.api.Dialog;
import tg.api.Message;
import tg.api.Peer;
import tg.api.ReactionSummary;
import tg.ui.ChatScreen;
import tg.ui.DialogListScreen;
import tg.ui.Theme;

/**
 * Produces privacy-safe project screenshots from the real Canvas UI.
 *
 * Every name, peer id and message below is fictional. The renderer never
 * starts the MIDlet, reads RMS or touches the network.
 */
public final class ShowcaseRenderer
{
    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int SCALE = 2;

    private ShowcaseRenderer() { }

    public static void main(String[] args) throws Exception
    {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        DeviceFactory.setDevice(new SizedDevice(WIDTH, HEIGHT));

        File output = new File(args.length == 0
                ? "docs/screenshots" : args[0]);
        if (!output.exists() && !output.mkdirs())
        {
            throw new IllegalStateException("Cannot create " + output);
        }

        renderDialogs(new File(output, "dialog-list.png"));
        renderWeekendChat(new File(output, "weekend-chat.png"));
        renderJ2meChat(new File(output, "j2me-club-dark.png"));
        System.out.println("Rendered 3 fictional screenshots to "
                + output.getCanonicalPath());
    }

    private static void renderDialogs(File file) throws Exception
    {
        ExposedDialogs screen =
                new ExposedDialogs(Theme.byId(Theme.LIGHT));
        Dialog[] dialogs = new Dialog[] {
            dialog(Peer.USER, 101, "Maya", "The lake looks perfect!", 2,
                    false, false, 1784388600),
            dialog(Peer.CHAT, 202, "Weekend Plans",
                    "I will bring the old camera.", 5,
                    true, true, 1784387700),
            dialog(Peer.CHANNEL, 303, "J2ME Club",
                    "New build: 320x240 layout polish", 18,
                    false, false, 1784386800),
            dialog(Peer.USER, 404, "Leo", "Coffee at ten?", 0,
                    false, false, 1784300400),
            dialog(Peer.USER, 505, "Saved Messages",
                    "Packing list for Saturday", 0,
                    true, false, 1784214000)
        };
        screen.setStatus("online", "live");
        screen.setDialogs(dialogs, 0, dialogs.length, dialogs[0].peer);
        write(file, screen);
    }

    private static void renderWeekendChat(File file) throws Exception
    {
        Peer group = peer(Peer.CHAT, 202, "Weekend Plans", false);
        Peer maya = peer(Peer.USER, 101, "Maya", false);
        Peer leo = peer(Peer.USER, 404, "Leo", false);
        Message[] messages = new Message[] {
            message(14, group, maya, false, false,
                    "Meet at 10 by the north gate.", 1784388600),
            message(13, group, null, true, true,
                    "Perfect. I will bring the old camera.", 1784387700),
            message(12, group, leo, false, false,
                    "I can bring coffee and sandwiches.", 1784387100),
            message(11, group, maya, false, false,
                    "Picnic by the lake on Saturday?", 1784386500)
        };

        ExposedChat screen = new ExposedChat(Theme.byId(Theme.LIGHT));
        screen.setTitle(group.title);
        screen.setPeer(group);
        screen.setStatus("3 members");
        screen.resetMessages(messages);
        screen.scrollToEnd();
        write(file, screen);
    }

    private static void renderJ2meChat(File file) throws Exception
    {
        Peer channel = peer(Peer.CHANNEL, 303, "J2ME Club", false);
        Peer nina = peer(Peer.USER, 606, "Nina", false);
        Peer max = peer(Peer.USER, 707, "Max", false);
        Message newest = message(24, channel, null, true, true,
                "Runs smoothly on a 2011 phone.", 1784475900);
        newest.reactions = new ReactionSummary[] {
            reaction("\ud83d\udd25", 7, true)
        };
        Message[] messages = new Message[] {
            newest,
            message(23, channel, max, false, false,
                    "That tiny memory footprint is impressive.", 1784475300),
            message(22, channel, nina, false, false,
                    "The 320x240 layout is ready to test.", 1784474700),
            message(21, channel, max, false, false,
                    "Fresh build uploaded!", 1784474100)
        };

        ExposedChat screen = new ExposedChat(Theme.byId(Theme.DARK));
        screen.setTitle(channel.title);
        screen.setPeer(channel);
        screen.setStatus("online");
        screen.resetMessages(messages);
        screen.scrollToEnd();
        write(file, screen);
    }

    private static Dialog dialog(int kind, long id, String title,
                                 String preview, int unread, boolean pinned,
                                 boolean outgoing, int date)
    {
        Dialog dialog = new Dialog();
        dialog.peer = peer(kind, id, title,
                "Saved Messages".equals(title));
        dialog.lastMessage = preview;
        dialog.unreadCount = unread;
        dialog.pinned = pinned;
        dialog.lastMessageOutgoing = outgoing;
        dialog.date = date;
        return dialog;
    }

    private static Peer peer(int kind, long id, String title, boolean self)
    {
        Peer peer = new Peer(kind, id);
        peer.title = title;
        peer.self = self;
        return peer;
    }

    private static Message message(int id, Peer peer, Peer sender,
                                   boolean outgoing, boolean read,
                                   String text, int date)
    {
        Message message = new Message();
        message.id = id;
        message.peer = peer;
        message.sender = sender;
        message.outgoing = outgoing;
        message.read = read;
        message.text = text;
        message.date = date;
        return message;
    }

    private static ReactionSummary reaction(String emoji, int count,
                                            boolean chosen)
    {
        ReactionSummary reaction = new ReactionSummary();
        reaction.emoji = emoji;
        reaction.count = count;
        reaction.chosen = chosen;
        return reaction;
    }

    private static void write(File file, ExposedCanvas canvas)
            throws Exception
    {
        J2SEMutableImage image = new J2SEMutableImage(WIDTH, HEIGHT);
        canvas.render(image.getGraphics());
        int[] pixels = new int[WIDTH * HEIGHT];
        image.getRGB(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);

        BufferedImage nativeImage = new BufferedImage(
                WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        nativeImage.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        BufferedImage scaled = new BufferedImage(
                WIDTH * SCALE, HEIGHT * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(nativeImage, 0, 0,
                    scaled.getWidth(), scaled.getHeight(), null);
        }
        finally
        {
            graphics.dispose();
        }
        ImageIO.write(scaled, "png", file);
    }

    private interface ExposedCanvas
    {
        void render(Graphics graphics);
    }

    private static final class ExposedDialogs extends DialogListScreen
            implements ExposedCanvas
    {
        ExposedDialogs(Theme theme) { super(theme); }
        public void render(Graphics graphics) { paint(graphics); }
    }

    private static final class ExposedChat extends ChatScreen
            implements ExposedCanvas
    {
        ExposedChat(Theme theme) { super(theme); }
        public void render(Graphics graphics) { paint(graphics); }
    }

    private static final class SizedDevice implements Device
    {
        private final J2SEFontManager fonts = new J2SEFontManager();
        private final UIFactory ui = new J2SEDevice().getUIFactory();
        private final J2SEDeviceDisplay screen = new J2SEDeviceDisplay(null);

        SizedDevice(int width, int height)
        {
            fonts.init();
            screen.setDisplayRectangle(new Rectangle(0, 0, width, height));
            screen.setDisplayPaintable(new Rectangle(0, 0, width, height));
            screen.setIsColor(true);
            screen.setNumColors(16777216);
            screen.setNumAlphaLevels(256);
            screen.setBackgroundColor(
                    new org.microemu.device.impl.Color(0xffffff));
            screen.setForegroundColor(
                    new org.microemu.device.impl.Color(0x000000));
        }

        public void init() { }
        public void destroy() { }
        public String getName() { return "showcase"; }
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
}
