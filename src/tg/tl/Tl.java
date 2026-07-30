package tg.tl;

/**
 * TL constructor ids that are needed before any code generation exists.
 *
 * The MTProto handshake constructors are hand-written on purpose: the handoff
 * is explicit that generator work must not block the proof of concept, and
 * these few never change. Everything in the Telegram API layer proper is
 * generated - see tools/generate-tl.py.
 *
 * Ids are the CRC32 of the normalised constructor declaration; they are copied
 * from <a href="https://core.telegram.org/schema/mtproto">the MTProto schema</a>
 * rather than computed here.
 */
public final class Tl
{
    private Tl() { }

    // --- built-ins ---------------------------------------------------------
    public static final int VECTOR      = 0x1cb5c415;
    public static final int BOOL_TRUE   = 0x997275b5;
    public static final int BOOL_FALSE  = 0xbc799737;
    public static final int NULL        = 0x56730bcc;

    // --- auth key exchange -------------------------------------------------
    public static final int REQ_PQ_MULTI          = 0xbe7e8ef1;
    public static final int RES_PQ                = 0x05162463;
    public static final int P_Q_INNER_DATA_DC     = 0xa9f55f95;
    public static final int REQ_DH_PARAMS         = 0xd712e4be;
    // Note: server_DH_params_fail no longer exists in the schema. A failed
    // req_DH_params comes back as a bare -404 transport error instead, and any
    // request after it also returns -404 - the handshake must restart.
    public static final int SERVER_DH_PARAMS_OK   = 0xd0e8075c;
    public static final int SERVER_DH_INNER_DATA  = 0xb5890dba;
    public static final int CLIENT_DH_INNER_DATA  = 0x6643b654;
    public static final int SET_CLIENT_DH_PARAMS  = 0xf5045f1f;
    public static final int DH_GEN_OK             = 0x3bcbf734;
    public static final int DH_GEN_RETRY          = 0x46dc1fb9;
    public static final int DH_GEN_FAIL           = 0xa69dae02;

    // --- service messages --------------------------------------------------
    public static final int RPC_RESULT            = 0xf35c6d01;
    public static final int RPC_ERROR             = 0x2144ca19;
    public static final int MSG_CONTAINER         = 0x73f1f8dc;
    public static final int NEW_SESSION_CREATED   = 0x9ec20908;
    public static final int BAD_MSG_NOTIFICATION  = 0xa7eff811;
    public static final int BAD_SERVER_SALT       = 0xedab447b;
    public static final int MSGS_ACK              = 0x62d6b459;
    public static final int PONG                  = 0x347773c5;
    public static final int PING                  = 0x7abe77ec;
    public static final int PING_DELAY_DISCONNECT = 0xf3427b8c;
    public static final int GZIP_PACKED           = 0x3072cfa1;
    public static final int MSG_DETAILED_INFO     = 0x276d3ec6;
    public static final int MSG_NEW_DETAILED_INFO = 0x809db6df;
    public static final int FUTURE_SALTS          = 0xae500895;
    public static final int DESTROY_SESSION_OK    = 0xe22045fc;
    public static final int DESTROY_SESSION_NONE  = 0x62d350c9;
    public static final int MSG_RESEND_REQ        = 0x7d861a08;
    public static final int MSGS_STATE_REQ        = 0xda69fb52;
    public static final int MSGS_STATE_INFO       = 0x04deb57d;
    public static final int MSGS_ALL_INFO         = 0x8cc0d131;

    // --- invocation wrappers ----------------------------------------------
    public static final int INVOKE_WITH_LAYER     = 0xda9b0d0d;
    public static final int INIT_CONNECTION       = 0xc1cd5ea9;
    public static final int INVOKE_AFTER_MSG      = 0xcb9f372d;

    /** Readable name for a constructor id, for diagnostics on a device with no debugger. */
    public static String name(int id)
    {
        switch (id)
        {
            case VECTOR:                 return "vector";
            case BOOL_TRUE:              return "boolTrue";
            case BOOL_FALSE:             return "boolFalse";
            case RES_PQ:                 return "resPQ";
            case SERVER_DH_PARAMS_OK:    return "server_DH_params_ok";
            case SERVER_DH_INNER_DATA:   return "server_DH_inner_data";
            case DH_GEN_OK:              return "dh_gen_ok";
            case DH_GEN_RETRY:           return "dh_gen_retry";
            case DH_GEN_FAIL:            return "dh_gen_fail";
            case RPC_RESULT:             return "rpc_result";
            case RPC_ERROR:              return "rpc_error";
            case MSG_CONTAINER:          return "msg_container";
            case NEW_SESSION_CREATED:    return "new_session_created";
            case BAD_MSG_NOTIFICATION:   return "bad_msg_notification";
            case BAD_SERVER_SALT:        return "bad_server_salt";
            case MSGS_ACK:               return "msgs_ack";
            case PONG:                   return "pong";
            case GZIP_PACKED:            return "gzip_packed";
            case MSG_DETAILED_INFO:      return "msg_detailed_info";
            case MSG_NEW_DETAILED_INFO:  return "msg_new_detailed_info";
            case FUTURE_SALTS:           return "future_salts";
            case MSGS_STATE_INFO:        return "msgs_state_info";
            case MSGS_ALL_INFO:          return "msgs_all_info";
            default:                     return "0x" + Integer.toHexString(id);
        }
    }
}
