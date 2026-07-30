package tg.api;

import tg.tl.TlObj;

/** Bounded, UI-ready user profile with static photo references only. */
public final class Profile
{
    public Peer peer;
    public String about = "";
    public PhotoRef photo;

    public static Profile from(TlObj fullReply, Peer requested, PeerCache peers)
    {
        Profile out = new Profile();
        if (fullReply != null && fullReply.id == Api.USERS_USER_FULL)
        {
            peers.absorb(fullReply.vec(Api.F_USERS_USER_FULL__USERS),
                    fullReply.vec(Api.F_USERS_USER_FULL__CHATS));
            TlObj full = fullReply.obj(Api.F_USERS_USER_FULL__FULL_USER);
            if (full != null && full.id == Api.USER_FULL)
            {
                out.about = full.strOrEmpty(Api.F_USER_FULL__ABOUT);
                out.photo = PhotoRef.from(
                        full.obj(Api.F_USER_FULL__PROFILE_PHOTO));
            }
        }
        out.peer = peers.resolve(requested);
        return out;
    }
}
