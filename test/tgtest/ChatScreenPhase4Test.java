package tgtest;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import org.microemu.device.DeviceFactory;
import org.microemu.device.j2se.J2SEMutableImage;

import tg.api.Media;
import tg.api.Message;
import tg.api.PhotoRef;
import tg.api.PhotoSizeRef;
import tg.api.ReactionSummary;
import tg.ui.ChatScreen;
import tg.ui.PhotoScreen;
import tg.ui.ReactionScreen;

/** Focus, activation, update anchoring and inline/text-only layout checks. */
public final class ChatScreenPhase4Test implements Test
{
    public String name() { return "ui/phase4-message-focus-media"; }

    public void run()
    {
        DeviceFactory.setDevice(new UiTestDevice("phase4", 320, 240));

        Message older = message(1, "old");
        Message photo = message(2, "caption");
        photo.media = new Media();
        photo.media.kind = Media.PHOTO;
        photo.media.label = "[photo]";
        photo.media.photo = new PhotoRef();
        PhotoSizeRef stripped = new PhotoSizeRef();
        stripped.kind = PhotoSizeRef.STRIPPED;
        stripped.bytes = new byte[] { 1, 1, 1, 0 };
        photo.media.photo.sizes = new PhotoSizeRef[] { stripped };
        ReactionSummary reaction = new ReactionSummary();
        reaction.emoji = "\ud83d\udc4d";
        reaction.count = 3;
        photo.reactions = new ReactionSummary[] { reaction };

        ExposedChat chat = new ExposedChat();
        chat.resetMessages(new Message[] { photo, older });
        J2SEMutableImage thumbnail = new J2SEMutableImage(20, 48);
        Graphics thumbnailGraphics = thumbnail.getGraphics();
        thumbnailGraphics.setColor(0xff0000);
        thumbnailGraphics.fillRect(0, 0, 20, 48);
        chat.setThumbnail(2, thumbnail);
        J2SEMutableImage rendered = new J2SEMutableImage(320, 240);
        chat.render(rendered.getGraphics());
        int[] pixels = new int[320 * 240];
        rendered.getRGB(pixels, 0, 320, 0, 0, 320, 240);
        int redRows = 0;
        for (int y = 0; y < 240; y++)
        {
            boolean red = false;
            for (int x = 0; x < 24; x++)
            {
                if ((pixels[y * 320 + x] & 0xffffff) == 0xff0000)
                {
                    red = true;
                    break;
                }
            }
            if (red) { redRows++; }
        }
        Assert.isTrue("focused thumbnail keeps its lower half", redRows >= 46);
        Assert.equal("newest focused", 2, chat.focusedMessageId());
        chat.press(Canvas.KEY_NUM2);
        Assert.equal("up focuses previous message", 1, chat.focusedMessageId());

        final int[] activated = new int[1];
        chat.setActivationListener(new ChatScreen.ActivationListener()
        {
            public void onMessageActivated(int messageId)
            {
                activated[0] = messageId;
            }
        });
        chat.press(Canvas.KEY_NUM5);
        Assert.equal("fire activates focused message", 1, activated[0]);

        Message newest = message(3, "new update");
        chat.setMessages(new Message[] { newest, photo, older });
        Assert.equal("focus survives update", 1, chat.focusedMessageId());
        int inlineWithUpdate = chat.transcriptLineCount();

        chat.setMediaPreviews(false);
        Assert.isTrue("text-only removes thumbnail rows",
                chat.transcriptLineCount() < inlineWithUpdate);
        chat.setMediaPreviews(true);
        Assert.isTrue("inline restores thumbnail rows",
                chat.transcriptLineCount() > chatLinesWithoutPreview(
                        new Message[] { newest, photo, older }));

        ExposedReactions picker = new ExposedReactions();
        picker.setReactions(new String[] { "\ud83d\udc4d", "\u2764\ufe0f" },
                new String[] { "Like", "Love" },
                new String[] { "\ud83d\udc4d" });
        Assert.isTrue("chosen reaction is marked", picker.isChosen(0));
        Assert.equal("remove-all is part of populated palette",
                3, picker.itemCount());
        final int[] selectedReaction = new int[] { -1 };
        picker.setActivationListener(new ReactionScreen.ActivationListener()
        {
            public void onReactionSelected(int index)
            {
                selectedReaction[0] = index;
            }
            public void onRemoveAll() { selectedReaction[0] = 99; }
            public void onViewReactions() { selectedReaction[0] = 98; }
            public void onViewSource() { selectedReaction[0] = 97; }
        });
        picker.press(Canvas.KEY_NUM8);
        picker.press(Canvas.KEY_NUM5);
        Assert.equal("reaction picker fires selected sprite row",
                1, selectedReaction[0]);
        picker.setActions(true, "View in channel");
        Assert.equal("message actions precede reaction palette",
                5, picker.itemCount());
        picker.press(Canvas.KEY_NUM5);
        Assert.equal("reaction remains the default when actions are present",
                0, selectedReaction[0]);
        picker.press(Canvas.KEY_NUM2);
        picker.press(Canvas.KEY_NUM2);
        picker.press(Canvas.KEY_NUM5);
        Assert.equal("view reactions action", 98, selectedReaction[0]);
        picker.press(Canvas.KEY_NUM8);
        picker.press(Canvas.KEY_NUM5);
        Assert.equal("view source action", 97, selectedReaction[0]);

        ExposedPhoto photoScreen = new ExposedPhoto();
        photoScreen.setImage(new J2SEMutableImage(320, 480));
        Assert.equal("photo defaults to fit screen",
                photoScreen.viewportHeight(),
                photoScreen.image().getHeight());
        photoScreen.nextZoom();
        Assert.isTrue("zoom switches to fit width", photoScreen.isZoomed());
        Assert.equal("fit width retains scrollable height", 480,
                photoScreen.image().getHeight());
        photoScreen.nextZoom();
        Assert.equal("third zoom mode is fixed 5x", 2,
                photoScreen.zoomMode());
        photoScreen.render(new J2SEMutableImage(320, 240).getGraphics());

        Message bottomPhoto = message(100, "");
        bottomPhoto.media = photo.media;
        Message[] longChat = new Message[21];
        longChat[0] = bottomPhoto;
        for (int i = 1; i < longChat.length; i++)
        {
            longChat[i] = message(i, "message " + i);
        }
        ExposedChat focusChat = new ExposedChat();
        focusChat.resetMessages(longChat);
        for (int i = 0; i < 20; i++) { focusChat.press(Canvas.KEY_NUM2); }
        for (int i = 0; i < 20; i++) { focusChat.press(Canvas.KEY_NUM8); }
        Assert.equal("focus returns to final media message", 100,
                focusChat.focusedMessageId());
        Assert.isTrue("final message tail scrolls fully into view",
                focusChat.isAtEnd());
    }

    private static int chatLinesWithoutPreview(Message[] messages)
    {
        ExposedChat plain = new ExposedChat();
        plain.setMediaPreviews(false);
        plain.resetMessages(messages);
        return plain.transcriptLineCount();
    }

    private static Message message(int id, String text)
    {
        Message out = new Message();
        out.id = id;
        out.text = text;
        return out;
    }

    private static final class ExposedChat extends ChatScreen
    {
        void press(int keyCode) { keyPressed(keyCode); }
        void render(Graphics graphics) { paint(graphics); }
    }

    private static final class ExposedReactions extends ReactionScreen
    {
        void press(int keyCode) { keyPressed(keyCode); }
    }

    private static final class ExposedPhoto extends PhotoScreen
    {
        void render(Graphics graphics) { paint(graphics); }
    }
}
