package tg.api;

/** One page of a forum's topic list, plus the server's total. */
public final class ForumTopicPage
{
    public ForumTopic[] topics = new ForumTopic[0];

    /** Topics the forum holds in total, never below what this page carries. */
    public int total;
}
