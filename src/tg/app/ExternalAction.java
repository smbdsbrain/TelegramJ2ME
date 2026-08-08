package tg.app;

import tg.api.MessageEntity;

/** Validation boundary between Telegram message text and platformRequest. */
public final class ExternalAction
{
    public static final int EXTERNAL = 1;
    public static final int USERNAME = 2;
    public static final int USER_ID = 3;

    public static final int CONTINUE = 0;
    public static final int EXIT_REQUIRED = 1;

    public interface Launcher
    {
        boolean open(String uri) throws Exception;
    }

    public static final class Target
    {
        public int kind;
        public String label;
        public String value;
        public long userId;
    }

    private ExternalAction() { }

    public static Target target(MessageEntity entity, String message)
    {
        if (entity == null || message == null) { return null; }
        String selected = entity.text(message);
        if (selected.length() == 0 || selected.length() > 512) { return null; }
        Target target = new Target();
        target.label = selected;
        if (entity.type == MessageEntity.MENTION)
        {
            String username = selected.charAt(0) == '@'
                    ? selected.substring(1) : selected;
            if (!validUsername(username)) { return null; }
            target.kind = USERNAME;
            target.value = username;
            return target;
        }
        if (entity.type == MessageEntity.MENTION_NAME)
        {
            if (entity.userId <= 0) { return null; }
            target.kind = USER_ID;
            target.userId = entity.userId;
            target.value = "user #" + entity.userId;
            return target;
        }

        String uri;
        if (entity.type == MessageEntity.URL)
        {
            uri = selected;
            if (!hasWebScheme(uri)) { uri = "https://" + uri; }
        }
        else if (entity.type == MessageEntity.TEXT_URL)
        {
            uri = entity.value;
            if (!hasWebScheme(uri)) { return null; }
        }
        else if (entity.type == MessageEntity.EMAIL)
        {
            if (!validEmail(selected)) { return null; }
            uri = "mailto:" + selected;
        }
        else if (entity.type == MessageEntity.PHONE)
        {
            String phone = phone(selected);
            if (phone == null) { return null; }
            uri = "tel:" + phone;
        }
        else
        {
            return null;
        }
        if (!safeUri(uri)) { return null; }
        target.kind = EXTERNAL;
        target.value = uri;
        return target;
    }

    public static int request(Launcher launcher, String uri) throws Exception
    {
        if (launcher == null || !safeUri(uri))
        {
            throw new IllegalArgumentException("external target is invalid");
        }
        return launcher.open(uri) ? EXIT_REQUIRED : CONTINUE;
    }

    private static boolean hasWebScheme(String value)
    {
        if (value == null) { return false; }
        String lower = value.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean safeUri(String value)
    {
        if (value == null || value.length() == 0 || value.length() > 519)
        {
            return false;
        }
        String lower = value.toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("tel:") || lower.startsWith("mailto:")))
        {
            return false;
        }
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c <= 0x20 || c == 0x7f) { return false; }
        }
        return true;
    }

    private static boolean validUsername(String value)
    {
        if (value == null || value.length() < 1 || value.length() > 32)
        {
            return false;
        }
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_'))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean validEmail(String value)
    {
        int at = value.indexOf('@');
        return at > 0 && at == value.lastIndexOf('@')
                && at < value.length() - 1 && value.indexOf('.') > at + 1;
    }

    private static String phone(String value)
    {
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') { out.append(c); }
            else if (c == '+' && out.length() == 0) { out.append(c); }
            else if (c != ' ' && c != '-' && c != '(' && c != ')')
            {
                return null;
            }
        }
        int digits = out.length() > 0 && out.charAt(0) == '+'
                ? out.length() - 1 : out.length();
        return digits < 3 ? null : out.toString();
    }
}
