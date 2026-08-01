package tgtest;

import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Form;
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

import tg.api.AppSettings;
import tg.api.AvatarRef;
import tg.api.Dialog;
import tg.api.Message;
import tg.api.Peer;
import tg.app.ScreenStack;
import tg.mem.MemoryBudget;
import tg.mt.AuthKey;
import tg.mt.AuthKeyStore;
import tg.ui.ChatScreen;
import tg.ui.AvatarCache;
import tg.ui.DialogListScreen;
import tg.ui.Metrics;
import tg.ui.PhotoScreen;
import tg.ui.ReactionScreen;
import tg.ui.TextScreen;
import tg.ui.Theme;

/** Phase 7 palettes, geometry, dialog list and bounded navigation. */
public final class Phase7DesignTest implements Test
{
    public String name() { return "phase7/themes-layout-navigation"; }

    public void run() throws Exception
    {
        themesAndPersistence();
        metricsAndRendering();
        dialogListBehaviour();
        avatarCache();
        cachesAtTheirFloor();
        navigationStack();
    }

    private static void themesAndPersistence()
    {
        Assert.equal("theme count", 3, Theme.names().length);
        for (int i = 0; i < Theme.COUNT; i++)
        {
            Theme theme = Theme.byId(i);
            Assert.equal("theme id", i, theme.id);
            Assert.isTrue("body contrast " + theme.name,
                    contrast(theme.background, theme.text) >= 100);
            Assert.isTrue("header contrast " + theme.name,
                    contrast(theme.accent, theme.accentText) >= 80);
        }
        Assert.equal("unknown theme falls back", Theme.LIGHT,
                Theme.byId(999).id);

        MemoryStore store = new MemoryStore();
        AppSettings first = new AppSettings();
        first.themeId = Theme.DARK;
        first.save(store);
        AppSettings loaded = new AppSettings();
        loaded.load(store);
        Assert.equal("theme RMS round trip", Theme.DARK, loaded.themeId);
        store.saveString("ui.theme", "999");
        loaded.load(store);
        Assert.equal("invalid RMS theme fallback", Theme.LIGHT,
                loaded.themeId);
    }

    private static void metricsAndRendering() throws Exception
    {
        int[][] sizes = new int[][] {
            { 128, 128 }, { 176, 220 }, { 240, 320 },
            { 320, 240 }, { 640, 480 }
        };
        for (int s = 0; s < sizes.length; s++)
        {
            int width = sizes[s][0];
            int height = sizes[s][1];
            DeviceFactory.setDevice(new SizedDevice(width, height));
            Font font = Font.getFont(Font.FACE_PROPORTIONAL,
                    Font.STYLE_PLAIN, Font.SIZE_SMALL);
            Metrics metrics = new Metrics();
            metrics.update(width, height, font, font);
            Assert.isTrue("positive body " + width + "x" + height,
                    metrics.bodyHeight > 0);
            Assert.isTrue("header fits " + width + "x" + height,
                    metrics.headerHeight <= height);
            Assert.equal("body reaches native command bar", height,
                    metrics.bodyBottom);
            Assert.isTrue("row visible " + width + "x" + height,
                    metrics.visibleRows() >= 1);

            for (int themeId = 0; themeId < Theme.COUNT; themeId++)
            {
                Theme theme = Theme.byId(themeId);
                ExposedChat chat = new ExposedChat(theme);
                Message message = new Message();
                message.id = 1;
                message.text = "Adaptive message with a long line for wrapping";
                chat.resetMessages(new Message[] { message });
                render(chat, width, height);

                ExposedReactions reactions = new ExposedReactions(theme);
                reactions.setReactions(new String[] { "\ud83d\udc4d" },
                        new String[] { "Like" }, new String[0]);
                render(reactions, width, height);

                ExposedText text = new ExposedText(theme);
                render(text, width, height);

                ExposedPhoto photo = new ExposedPhoto(theme);
                photo.setImage(new J2SEMutableImage(
                        Math.max(1, width), Math.max(1, height * 2)));
                render(photo, width, height);
                Assert.isTrue("photo viewport positive",
                        photo.viewportHeight() > 0);

                ExposedDialogs list = new ExposedDialogs(theme);
                list.setDialogs(new Dialog[] { dialog(1, "One", 2) }, 1,
                        null);
                int[] pixels = render(list, width, height);
                Assert.equal("header palette", theme.accent,
                        pixels[metrics.padding * width + metrics.padding]
                                & 0xffffff);
            }
        }
    }

    private static void dialogListBehaviour()
    {
        DeviceFactory.setDevice(new SizedDevice(320, 240));
        Dialog[] values = new Dialog[20];
        for (int i = 0; i < values.length; i++)
        {
            values[i] = dialog(i + 1, "Chat " + (i + 1),
                    i == 7 ? 42 : 0);
            values[i].pinned = i == 0;
        }
        final long[] activated = new long[1];
        ExposedDialogs list = new ExposedDialogs(Theme.byId(Theme.DARK));
        list.setActivationListener(new DialogListScreen.ActivationListener()
        {
            public void onDialogActivated(Peer peer)
            {
                activated[0] = peer.id;
            }
        });
        list.setDialogs(values, values.length, values[7].peer);
        Assert.equal("logical selection", 7, list.selectedIndex());
        Assert.isTrue("selection visible",
                list.topIndex() <= list.selectedIndex());
        list.press(Canvas.KEY_NUM5);
        Assert.equal("fire activates peer", 8, activated[0]);

        Dialog[] reordered = new Dialog[values.length];
        reordered[0] = values[7];
        System.arraycopy(values, 0, reordered, 1, 7);
        System.arraycopy(values, 8, reordered, 8, values.length - 8);
        list.setDialogs(reordered, reordered.length, values[7].peer);
        Assert.equal("selection survives reorder", 0, list.selectedIndex());

        int[] pixels = render(list, 320, 240);
        Assert.isTrue("unread badge rendered",
                countColour(pixels, Theme.byId(Theme.DARK).badge) > 0);
        list.press(Canvas.KEY_NUM6);
        Assert.isTrue("right pages", list.selectedIndex() > 0);
    }

    private static void navigationStack()
    {
        DeviceFactory.setDevice(new SizedDevice(176, 220));
        ScreenStack stack = new ScreenStack();
        Displayable root = new Form("root");
        stack.resetRoot(root);
        Assert.isTrue("root state", stack.isRoot());
        for (int i = 0; i < 20; i++) { stack.push(new Form("s" + i)); }
        Assert.equal("bounded depth", stack.capacity(), stack.depth());
        Assert.isTrue("root retained", stack.root() == root);
        Displayable before = stack.current();
        Assert.isTrue("nested current", before != root);
        stack.pop();
        Assert.equal("pop depth", stack.capacity() - 1, stack.depth());
        stack.replace(new Form("replacement"));
        Assert.equal("replace keeps depth", stack.capacity() - 1,
                stack.depth());
        while (!stack.isRoot()) { stack.pop(); }
        Assert.isTrue("pop reaches root", stack.current() == root);
    }

    private static void avatarCache()
    {
        AvatarCache cache = new AvatarCache();
        Peer peer = new Peer(Peer.USER, 500);
        peer.avatar = new AvatarRef();
        peer.avatar.photoId = 600;
        Assert.isTrue("avatar load claim", cache.markLoading(peer));
        Assert.isTrue("duplicate avatar load suppressed",
                !cache.markLoading(peer));
        J2SEMutableImage image = new J2SEMutableImage(12, 12);
        image.getGraphics().setColor(0xff0000);
        image.getGraphics().fillRect(0, 0, 12, 12);
        cache.put(peer, image);
        Assert.isTrue("avatar cache hit", cache.get(peer) == image);

        for (int i = 0; i < cache.capacity() + 1; i++)
        {
            Peer item = new Peer(Peer.USER, 1000 + i);
            item.avatar = new AvatarRef();
            item.avatar.photoId = 2000 + i;
            cache.put(item, image);
        }
        Assert.isTrue("decoded avatar cache remains bounded",
                cache.get(peer) == null);
    }

    /**
     * The floor is the only configuration a handset below about a megabyte will
     * ever see, and it is reachable no other way: the desktop suite runs with
     * the reference profile, and no measured device has ever been small enough
     * to exercise it. Evicting correctly at two slots is a different code path
     * from evicting correctly at sixteen.
     */
    private static void cachesAtTheirFloor()
    {
        MemoryBudget.init(64 * 1024, 0, MemoryBudget.SOURCE_MEASURED);
        try
        {
            Assert.equal("a tiny heap still gets two avatar slots", 2,
                    new AvatarCache().capacity());
            Assert.equal("a tiny heap still gets two thumbnail slots", 2,
                    new ChatScreen(Theme.byId(Theme.LIGHT)).thumbnailCapacity());
            Assert.equal("a tiny heap still gets four screens", 4,
                    new ScreenStack().capacity());

            // A cache built directly with a bad number must floor itself rather
            // than trusting the caller.
            Assert.equal("an avatar cache floors its own capacity", 2,
                    new AvatarCache(0).capacity());
            Assert.equal("a screen stack floors its own capacity", 4,
                    new ScreenStack(1).capacity());

            AvatarCache small = new AvatarCache(2);
            J2SEMutableImage image = new J2SEMutableImage(4, 4);
            Peer first = avatarPeer(1);
            small.put(first, image);
            Assert.isTrue("two-slot cache holds its entry",
                    small.get(first) == image);
            small.put(avatarPeer(2), image);
            small.put(avatarPeer(3), image);
            Assert.isTrue("two-slot cache evicts the oldest",
                    small.get(first) == null);

            ChatScreen narrow = new ChatScreen(Theme.byId(Theme.LIGHT), 2);
            narrow.setThumbnail(11, image);
            narrow.setThumbnail(12, image);
            Assert.isTrue("two-slot thumbnails hold", narrow.hasThumbnail(11));
            narrow.setThumbnail(13, image);
            Assert.isFalse("two-slot thumbnails evict first in",
                    narrow.hasThumbnail(11));
            Assert.isTrue("two-slot thumbnails keep the newest",
                    narrow.hasThumbnail(13));
            narrow.clearThumbnails();
            Assert.isFalse("clearThumbnails releases every slot",
                    narrow.hasThumbnail(13));
        }
        finally { MemoryBudget.reset(); }
    }

    private static Peer avatarPeer(int id)
    {
        Peer peer = new Peer(Peer.USER, 7000 + id);
        peer.avatar = new AvatarRef();
        peer.avatar.photoId = 8000 + id;
        return peer;
    }

    private static Dialog dialog(int id, String title, int unread)
    {
        Dialog dialog = new Dialog();
        dialog.peer = new Peer(id % 3, id);
        dialog.peer.title = title;
        dialog.lastMessage = "Preview for " + title;
        dialog.unreadCount = unread;
        dialog.date = (int) (System.currentTimeMillis() / 1000L);
        return dialog;
    }

    private static int[] render(ExposedCanvas canvas, int width, int height)
    {
        J2SEMutableImage image = new J2SEMutableImage(width, height);
        canvas.render(image.getGraphics());
        int[] pixels = new int[width * height];
        image.getRGB(pixels, 0, width, 0, 0, width, height);
        return pixels;
    }

    private static int contrast(int first, int second)
    {
        return Math.abs(luma(first) - luma(second));
    }

    private static int luma(int colour)
    {
        int r = (colour >>> 16) & 255;
        int g = (colour >>> 8) & 255;
        int b = colour & 255;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int countColour(int[] pixels, int colour)
    {
        int count = 0;
        for (int i = 0; i < pixels.length; i++)
        {
            if ((pixels[i] & 0xffffff) == colour) { count++; }
        }
        return count;
    }

    private interface ExposedCanvas
    {
        void render(Graphics graphics);
    }

    private static final class ExposedChat extends ChatScreen
            implements ExposedCanvas
    {
        ExposedChat(Theme theme) { super(theme); }
        public void render(Graphics graphics) { paint(graphics); }
    }

    private static final class ExposedReactions extends ReactionScreen
            implements ExposedCanvas
    {
        ExposedReactions(Theme theme) { super(theme); }
        public void render(Graphics graphics) { paint(graphics); }
    }

    private static final class ExposedText extends TextScreen
            implements ExposedCanvas
    {
        ExposedText(Theme theme)
        {
            super("Text", new String[] { "one", "two", "three" }, theme);
        }
        public void render(Graphics graphics) { paint(graphics); }
    }

    private static final class ExposedPhoto extends PhotoScreen
            implements ExposedCanvas
    {
        ExposedPhoto(Theme theme) { super(theme); }
        public void render(Graphics graphics) { paint(graphics); }
    }

    private static final class ExposedDialogs extends DialogListScreen
            implements ExposedCanvas
    {
        ExposedDialogs(Theme theme) { super(theme); }
        public void render(Graphics graphics) { paint(graphics); }
        void press(int keyCode) { keyPressed(keyCode); }
    }

    private static final class MemoryStore implements AuthKeyStore
    {
        private final java.util.Hashtable values = new java.util.Hashtable();
        public AuthKey load(int dcId, boolean test) { return null; }
        public void save(AuthKey key) { }
        public void clear(int dcId, boolean test) { }
        public String loadString(String name)
        {
            return (String) values.get(name);
        }
        public void saveString(String name, String value)
        {
            if (value == null) { values.remove(name); }
            else { values.put(name, value); }
        }
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
        public String getName() { return "phase7"; }
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
