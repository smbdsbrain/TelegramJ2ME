package tgtest;

import tg.api.Api;
import tg.api.MessageEntity;
import tg.app.ExternalAction;
import tg.tl.TlObj;

/** UTF-16 entity bounds, safe targets and explicit platform launch. */
public final class MessageEntityTest implements Test
{
    public String name() { return "text/message-entities-links"; }

    public void run() throws Exception
    {
        parsesSupportedEntities();
        rejectsBrokenUtf16AndOverlap();
        normalizesSafeTargets();
        launchOutcomeIsExplicit();
    }

    private static void parsesSupportedEntities()
    {
        String text = "\ud83d\ude00 https://x.test @alice a@b.test +1 (234)";
        TlObj url = entity(Api.MESSAGE_ENTITY_URL,
                text.indexOf("https"), "https://x.test".length());
        TlObj mention = entity(Api.MESSAGE_ENTITY_MENTION,
                text.indexOf("@alice"), 6);
        TlObj email = entity(Api.MESSAGE_ENTITY_EMAIL,
                text.indexOf("a@b"), "a@b.test".length());
        TlObj phone = entity(Api.MESSAGE_ENTITY_PHONE,
                text.indexOf("+1"), "+1 (234)".length());
        MessageEntity[] parsed = MessageEntity.from(
                new TlObj[] { url, mention, email, phone }, text, 8);
        Assert.equal("supported count", 4, parsed.length);
        Assert.equal("UTF-16 offset retained", 3, parsed[0].offset);
        Assert.equal("mention text", "@alice", parsed[1].text(text));
    }

    private static void rejectsBrokenUtf16AndOverlap()
    {
        String text = "A\ud83d\ude00BCDEF";
        TlObj split = entity(Api.MESSAGE_ENTITY_URL, 1, 1);
        TlObj valid = entity(Api.MESSAGE_ENTITY_URL, 3, 2);
        TlObj overlap = entity(Api.MESSAGE_ENTITY_EMAIL, 4, 2);
        TlObj outside = entity(Api.MESSAGE_ENTITY_PHONE, 99, 3);
        MessageEntity[] parsed = MessageEntity.from(
                new TlObj[] { split, valid, overlap, outside }, text, 8);
        Assert.equal("only valid non-overlapping range", 1, parsed.length);
        Assert.equal("valid starts after emoji", 3, parsed[0].offset);
    }

    private static void normalizesSafeTargets()
    {
        Assert.equal("schemeless URL becomes https",
                "https://example.test", target(MessageEntity.URL,
                        "example.test", null).value);
        Assert.equal("phone is compact",
                "tel:+1234", target(MessageEntity.PHONE,
                        "+1 (234)", null).value);
        Assert.equal("email action",
                "mailto:a@b.test", target(MessageEntity.EMAIL,
                        "a@b.test", null).value);
        Assert.equal("username is not made into a URL",
                ExternalAction.USERNAME, target(MessageEntity.MENTION,
                        "@alice", null).kind);
        Assert.isTrue("unsupported text URL stays plain",
                target(MessageEntity.TEXT_URL, "label", "tg://unsafe") == null);
    }

    private static void launchOutcomeIsExplicit() throws Exception
    {
        final String[] opened = new String[1];
        int outcome = ExternalAction.request(new ExternalAction.Launcher()
        {
            public boolean open(String uri)
            {
                opened[0] = uri;
                return false;
            }
        }, "https://example.test");
        Assert.equal("launcher receives exact URI", "https://example.test",
                opened[0]);
        Assert.equal("false means no exit required", ExternalAction.CONTINUE,
                outcome);
        outcome = ExternalAction.request(new ExternalAction.Launcher()
        {
            public boolean open(String uri) { return true; }
        }, "mailto:a@b.test");
        Assert.equal("true means exit required", ExternalAction.EXIT_REQUIRED,
                outcome);
    }

    private static ExternalAction.Target target(int type, String text,
                                                 String value)
    {
        MessageEntity entity = new MessageEntity();
        entity.type = type;
        entity.offset = 0;
        entity.length = text.length();
        entity.value = value;
        return ExternalAction.target(entity, text);
    }

    private static TlObj entity(int id, int offset, int length)
    {
        TlObj value = new TlObj(id, 3);
        value.nums[0] = offset;
        value.nums[1] = length;
        value.refs = new Object[3];
        return value;
    }
}
