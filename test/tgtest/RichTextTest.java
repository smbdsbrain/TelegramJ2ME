package tgtest;

import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Font;

import org.microemu.device.DeviceFactory;
import org.microemu.device.j2se.J2SEMutableImage;

import tg.api.Message;
import tg.api.MessageEntity;
import tg.ui.ChatScreen;
import tg.ui.EmojiText;
import tg.ui.RichText;
import tg.ui.Theme;

/** Mixed-font wrapping, blockquotes and static spoiler concealment. */
public final class RichTextTest implements Test
{
    public String name() { return "ui/rich-text-spoilers"; }

    public void run()
    {
        DeviceFactory.setDevice(new UiTestDevice("rich-text", 320, 240));
        mixedStylesUseCombinedFontMetrics();
        blockquoteUsesItsInsetDuringWrap();
        spoilerRevealDoesNotReflow();
    }

    private static void mixedStylesUseCombinedFontMetrics()
    {
        String text = "Wide";
        MessageEntity[] entities = new MessageEntity[] {
                entity(MessageEntity.BOLD, 0, text.length()),
                entity(MessageEntity.ITALIC, 0, text.length()),
                entity(MessageEntity.UNDERLINE, 0, text.length()),
                entity(MessageEntity.CODE, 0, text.length()),
                entity(MessageEntity.STRIKE, 0, text.length())
        };
        Font expected = Font.getFont(Font.FACE_MONOSPACE,
                Font.STYLE_BOLD | Font.STYLE_ITALIC | Font.STYLE_UNDERLINED,
                Font.SIZE_SMALL);
        Assert.equal("overlapping styles combine into one font metric",
                EmojiText.stringWidth(text, expected),
                new RichText().width(text, 0, text.length(), entities));
    }

    private static void blockquoteUsesItsInsetDuringWrap()
    {
        RichText renderer = new RichText();
        ExposedChat ordinary = new ExposedChat(Theme.byId(Theme.LIGHT));
        int contentWidth = ordinary.thumbnailWidth();
        String text = "";
        while (renderer.width(text + ".", 0, text.length() + 1,
                new MessageEntity[0]) <= contentWidth - 6)
        {
            text += ".";
        }
        text += ".";
        // Ensure the fixture fits the ordinary body but not the body after
        // the quote's six-pixel inset on the 320x240 test device.
        Assert.isTrue("fixture fits ordinary content",
                renderer.width(text, 0, text.length(),
                        new MessageEntity[0]) <= contentWidth);

        Message plain = message(1, text);
        ordinary.resetMessages(new Message[] { plain });

        Message quoted = message(2, text);
        quoted.entities = new MessageEntity[] {
                entity(MessageEntity.BLOCKQUOTE, 0, text.length())
        };
        ExposedChat quote = new ExposedChat(Theme.byId(Theme.LIGHT));
        quote.resetMessages(new Message[] { quoted });
        Assert.isTrue("quote inset participates in wrapping",
                quote.transcriptLineCount() > ordinary.transcriptLineCount());
    }

    private static void spoilerRevealDoesNotReflow()
    {
        for (int themeId = 0; themeId < Theme.COUNT; themeId++)
        {
            Theme theme = Theme.byId(themeId);
            Message newest = message(2, "plain row");
            Message secret = message(1, "secret https://example.test");
            secret.entities = new MessageEntity[] {
                    entity(MessageEntity.SPOILER, 0, secret.text.length()),
                    entity(MessageEntity.URL, 7, "https://example.test".length()),
                    entity(MessageEntity.BOLD, 0, 6),
                    entity(MessageEntity.ITALIC, 0, 6),
                    entity(MessageEntity.UNDERLINE, 0, 6),
                    entity(MessageEntity.STRIKE, 0, 6),
                    entity(MessageEntity.CODE, 0, 6)
            };
            ExposedChat chat = new ExposedChat(theme);
            chat.resetMessages(new Message[] { newest, secret });
            Assert.isTrue("spoiler starts concealed in theme " + themeId,
                    chat.hasConcealedSpoilers(1));
            int lines = chat.transcriptLineCount();
            int layouts = chat.layoutCount();
            int[] concealed = render(chat);
            int dotsBefore = countColor(concealed, theme.secondaryText);

            chat.revealSpoilers(1);
            Assert.isFalse("spoiler is revealed in theme " + themeId,
                    chat.hasConcealedSpoilers(1));
            Assert.equal("reveal keeps line count in theme " + themeId,
                    lines, chat.transcriptLineCount());
            Assert.equal("reveal does not invoke layout in theme " + themeId,
                    layouts, chat.layoutCount());
            int[] revealed = render(chat);
            Assert.isTrue("concealed and revealed pixels differ in theme " + themeId,
                    different(concealed, revealed) > 0);
            if (themeId != Theme.HIGH_CONTRAST)
            {
                Assert.isTrue("dot colour disappears after reveal in theme "
                        + themeId,
                        dotsBefore > countColor(revealed, theme.secondaryText));
            }
        }
    }

    private static int[] render(ExposedChat chat)
    {
        J2SEMutableImage image = new J2SEMutableImage(320, 240);
        chat.render(image.getGraphics());
        int[] pixels = new int[320 * 240];
        image.getRGB(pixels, 0, 320, 0, 0, 320, 240);
        return pixels;
    }

    private static int different(int[] first, int[] second)
    {
        int count = 0;
        for (int i = 0; i < first.length; i++)
        {
            if (first[i] != second[i]) { count++; }
        }
        return count;
    }

    private static int countColor(int[] pixels, int color)
    {
        int count = 0;
        for (int i = 0; i < pixels.length; i++)
        {
            if ((pixels[i] & 0xffffff) == color) { count++; }
        }
        return count;
    }

    private static Message message(int id, String text)
    {
        Message message = new Message();
        message.id = id;
        message.text = text;
        return message;
    }

    private static MessageEntity entity(int type, int offset, int length)
    {
        MessageEntity entity = new MessageEntity();
        entity.type = type;
        entity.offset = offset;
        entity.length = length;
        return entity;
    }

    private static final class ExposedChat extends ChatScreen
    {
        ExposedChat(Theme theme) { super(theme); }
        void render(Graphics graphics) { paint(graphics); }
    }
}
