package tg.api;

import java.util.Vector;

import tg.mt.DcEndpoint;
import tg.tl.TlObj;

/** In-memory IPv4 DC directory refreshed from every help.getConfig result. */
public final class DcDirectory
{
    private final Vector entries = new Vector();

    public synchronized void absorb(TlObj config)
    {
        if (config == null || config.id != Api.CONFIG) { return; }
        TlObj[] options = config.vec(Api.F_CONFIG__DC_OPTIONS);
        entries.removeAllElements();
        for (int i = 0; i < options.length; i++)
        {
            TlObj option = options[i];
            if (option == null || option.id != Api.DC_OPTION
                    || option.num(Api.F_DC_OPTION__IPV6) != 0
                    || option.num(Api.F_DC_OPTION__CDN) != 0)
            {
                continue;
            }
            String host = option.strOrEmpty(Api.F_DC_OPTION__IP_ADDRESS);
            int port = option.intAt(Api.F_DC_OPTION__PORT);
            int id = option.intAt(Api.F_DC_OPTION__ID);
            if (host.length() == 0 || host.indexOf(':') >= 0
                    || port < 1 || port > 65535 || id < 1)
            {
                continue;
            }
            entries.addElement(new DcEndpoint(id, host, port,
                    option.num(Api.F_DC_OPTION__MEDIA_ONLY) != 0));
        }
    }

    public synchronized DcEndpoint endpoint(int dcId, boolean media)
    {
        DcEndpoint regular = null;
        for (int i = 0; i < entries.size(); i++)
        {
            DcEndpoint endpoint = (DcEndpoint) entries.elementAt(i);
            if (endpoint.dcId != dcId) { continue; }
            if (media && endpoint.mediaOnly) { return endpoint; }
            if (!endpoint.mediaOnly && regular == null) { regular = endpoint; }
        }
        return regular == null ? DcEndpoint.builtin(dcId) : regular;
    }
}
