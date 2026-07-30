#!/usr/bin/env python3
"""
Check the hand-written constructor ids in src/tg/tl/Tl.java against the official
schemas.

Why bother: a TL constructor id is a CRC32 of the constructor's declaration, so
a single wrong digit produces a request the server silently ignores or a
response we misparse into nonsense. On a handset with no debugger that is close
to undiagnosable, and the handoff explicitly allows hand-writing only the
handful of MTProto handshake constructors - this is what keeps that shortcut
honest.

Everything in the Telegram API layer proper is generated, not hand-written; see
tools/generate-tl.py.

Usage:
    python tools/verify-tl-ids.py
"""

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TL_JAVA = REPO / "src" / "tg" / "tl" / "Tl.java"
SCHEMAS = [REPO / "schema" / "mtproto.json", REPO / "schema" / "api.json"]

# Java constant name -> TL predicate/method name.
EXPECTED = {
    "VECTOR": "vector",
    "BOOL_TRUE": "boolTrue",
    "BOOL_FALSE": "boolFalse",
    "NULL": "null",

    "REQ_PQ_MULTI": "req_pq_multi",
    "RES_PQ": "resPQ",
    "P_Q_INNER_DATA_DC": "p_q_inner_data_dc",
    "REQ_DH_PARAMS": "req_DH_params",
    "SERVER_DH_PARAMS_OK": "server_DH_params_ok",
    "SERVER_DH_INNER_DATA": "server_DH_inner_data",
    "CLIENT_DH_INNER_DATA": "client_DH_inner_data",
    "SET_CLIENT_DH_PARAMS": "set_client_DH_params",
    "DH_GEN_OK": "dh_gen_ok",
    "DH_GEN_RETRY": "dh_gen_retry",
    "DH_GEN_FAIL": "dh_gen_fail",

    "RPC_RESULT": "rpc_result",
    "RPC_ERROR": "rpc_error",
    "MSG_CONTAINER": "msg_container",
    "NEW_SESSION_CREATED": "new_session_created",
    "BAD_MSG_NOTIFICATION": "bad_msg_notification",
    "BAD_SERVER_SALT": "bad_server_salt",
    "MSGS_ACK": "msgs_ack",
    "PONG": "pong",
    "PING": "ping",
    "PING_DELAY_DISCONNECT": "ping_delay_disconnect",
    "GZIP_PACKED": "gzip_packed",
    "MSG_DETAILED_INFO": "msg_detailed_info",
    "MSG_NEW_DETAILED_INFO": "msg_new_detailed_info",
    "FUTURE_SALTS": "future_salts",
    "DESTROY_SESSION_OK": "destroy_session_ok",
    "DESTROY_SESSION_NONE": "destroy_session_none",
    "MSG_RESEND_REQ": "msg_resend_req",
    "MSGS_STATE_REQ": "msgs_state_req",
    "MSGS_STATE_INFO": "msgs_state_info",
    "MSGS_ALL_INFO": "msgs_all_info",

    "INVOKE_WITH_LAYER": "invokeWithLayer",
    "INIT_CONNECTION": "initConnection",
    "INVOKE_AFTER_MSG": "invokeAfterMsg",
}


def load_schema_ids():
    """predicate/method name -> id, as an unsigned 32-bit value."""
    ids = {}
    for path in SCHEMAS:
        if not path.exists():
            print("verify-tl-ids: missing %s - run the schema download first"
                  % path.relative_to(REPO), file=sys.stderr)
            sys.exit(2)
        data = json.loads(path.read_text(encoding="utf-8"))
        for entry in data.get("constructors", []):
            ids[entry["predicate"]] = int(entry["id"]) & 0xFFFFFFFF
        for entry in data.get("methods", []):
            ids[entry["method"]] = int(entry["id"]) & 0xFFFFFFFF
    return ids


def load_java_ids():
    text = TL_JAVA.read_text(encoding="utf-8")
    found = {}
    for match in re.finditer(
            r"public\s+static\s+final\s+int\s+([A-Z0-9_]+)\s*=\s*(0x[0-9a-fA-F]+)\s*;", text):
        found[match.group(1)] = int(match.group(2), 16) & 0xFFFFFFFF
    return found


def main():
    schema = load_schema_ids()
    java = load_java_ids()

    problems = []
    checked = 0

    for const, tl_name in sorted(EXPECTED.items()):
        if const not in java:
            problems.append("%s is listed here but missing from Tl.java" % const)
            continue
        if tl_name not in schema:
            problems.append("%s -> '%s' is not in the official schema" % (const, tl_name))
            continue
        want = schema[tl_name]
        got = java[const]
        checked += 1
        if want != got:
            problems.append("%s (%s): Tl.java has 0x%08x, schema says 0x%08x"
                            % (const, tl_name, got, want))

    unmapped = sorted(set(java) - set(EXPECTED))
    for const in unmapped:
        problems.append("%s is in Tl.java but not mapped to a TL name here" % const)

    print("verify-tl-ids: checked %d constructor id(s) against %s"
          % (checked, ", ".join(p.name for p in SCHEMAS)))

    if problems:
        print("", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        print("", file=sys.stderr)
        print("  A wrong constructor id produces a request the server ignores or a",
              file=sys.stderr)
        print("  response we misparse. Fix Tl.java from the schema.", file=sys.stderr)
        return 1

    print("verify-tl-ids: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
