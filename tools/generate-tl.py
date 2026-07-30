#!/usr/bin/env python3
"""
Generate the TL schema table for the device.

Design
------
The obvious mapping - one Java class per TL constructor - would produce well
over a thousand classes. On a handset that means a huge JAR, slow class loading
and a lot of constant-pool duplication, for types most of which we only ever
need to *skip past* rather than read.

So this emits a table instead: a compact description of each constructor's field
layout, which tg.tl.TlParser walks. Skipping a Photo inside a Message costs no
class, no allocation and no code - just a few table lookups.

The table is emitted as a Java String constant rather than as int[] literals.
Large array initialisers compile into bytecode that stores each element
individually, which is enormous; a String lives in the constant pool as UTF-8
and is decoded once at class init.

Only the transitive closure of config/tl-whitelist.txt is generated.

Usage:
    python tools/generate-tl.py
"""

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SCHEMA_API = REPO / "schema" / "api.json"
SCHEMA_MTPROTO = REPO / "schema" / "mtproto.json"
WHITELIST = REPO / "config" / "tl-whitelist.txt"
OUT_DIR = REPO / "generated" / "tg" / "api"

# Field kinds. Must match tg.tl.TlParser.
K_INT = 1
K_LONG = 2
K_INT128 = 3
K_INT256 = 4
K_DOUBLE = 5
K_BYTES = 6
K_BOOL = 7
K_OBJECT = 8
K_FLAGS = 9
K_TRUE = 10
K_VECTOR = 11
K_BARE_VECTOR = 12
K_STRING = 13

BARE_TYPES = {
    "int": K_INT,
    "#": K_FLAGS,
    "long": K_LONG,
    "int128": K_INT128,
    "int256": K_INT256,
    "double": K_DOUBLE,
    "string": K_STRING,
    "bytes": K_BYTES,
    "Bool": K_BOOL,
    "true": K_TRUE,
}


def fail(msg):
    print("generate-tl: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


class Schema:
    def __init__(self):
        self.constructors = {}      # predicate -> entry
        self.by_id = {}             # id -> entry
        self.by_type = {}           # abstract type -> [entry]
        self.methods = {}           # method name -> entry

        for path, origin in ((SCHEMA_MTPROTO, "mt"), (SCHEMA_API, "api")):
            if not path.exists():
                fail("missing %s" % path)
            data = json.loads(path.read_text(encoding="utf-8"))
            for e in data.get("constructors", []):
                e = dict(e)
                e["origin"] = origin
                e["id"] = int(e["id"]) & 0xFFFFFFFF
                e["name"] = e["predicate"]
                self.constructors[e["predicate"]] = e
                self.by_id[e["id"]] = e
                self.by_type.setdefault(e["type"], []).append(e)
            for e in data.get("methods", []):
                e = dict(e)
                e["id"] = int(e["id"]) & 0xFFFFFFFF
                e["name"] = e["method"]
                self.methods[e["method"]] = e


def parse_type(type_str):
    """
    Turn a TL type expression into (kind, element_kind, referenced_type).

    referenced_type is the abstract type name whose constructors must also be
    generated, or None for primitives.
    """
    t = type_str

    # Conditional fields carry their condition in the type: "flags.3?true"
    if "?" in t:
        t = t.split("?", 1)[1]

    vector = re.match(r"^[Vv]ector<(.+)>$", t)
    if vector:
        inner = vector.group(1)
        # A percent sign marks a bare type, used for message containers.
        bare = inner.startswith("%")
        inner = inner.lstrip("%")
        if inner in BARE_TYPES:
            return (K_VECTOR, BARE_TYPES[inner], None)
        return (K_BARE_VECTOR if bare else K_VECTOR, K_OBJECT, inner)

    if t.startswith("%"):
        return (K_OBJECT, 0, t[1:])

    if t in BARE_TYPES:
        return (BARE_TYPES[t], 0, None)

    # !X is a generic query slot; it only appears in wrapper methods that we
    # build by hand, never in a response we parse.
    if t in ("!X", "X", "Type", "Object"):
        return (K_OBJECT, 0, None)

    return (K_OBJECT, 0, t)


def flag_condition(type_str):
    """
    ("flags2", 10) for a field typed "flags2.10?true", or None.

    The field NAME matters, not just the bit: five constructors - user,
    message, channel, userFull, channelFull - carry both `flags` and `flags2`,
    and their later fields are conditional on the second one. Reading the bit
    number alone and testing it against the first flags field desynchronises
    parsing of every user and message.
    """
    m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\.(\d+)\?", type_str)
    return (m.group(1), int(m.group(2))) if m else None


def encode_condition(entry, type_str):
    """
    Pack a field's condition into one char.

    0 means unconditional. Otherwise the value is (slot * 32 + bit) + 1, where
    slot is which `#` field of this constructor the condition refers to, in
    declaration order.
    """
    cond = flag_condition(type_str)
    if cond is None:
        return 0
    name, bit = cond

    slots = [p["name"] for p in entry["params"] if p["type"] == "#"]
    if name not in slots:
        fail("constructor '%s' has a field conditional on '%s', which is not one "
             "of its flags fields %s" % (entry["name"], name, slots))
    slot = slots.index(name)

    if slot > 3 or bit > 31:
        fail("constructor '%s': condition %s.%d does not fit the encoding"
             % (entry["name"], name, bit))
    return slot * 32 + bit + 1


def read_whitelist():
    methods, types, fields = [], [], []
    section = None
    for raw in WHITELIST.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            continue
        if section == "methods":
            methods.append(line)
        elif section == "types":
            types.append(line)
        elif section == "fields":
            fields.append(line)
    return methods, types, fields


def resolve_fields(schema, field_specs):
    """
    Turn "user.first_name" into (CONSTANT_NAME, field index).

    Constructor names contain dots too (messages.dialogs.users), so the split
    is on the LAST dot and the remainder must name a known constructor.
    """
    out = []
    for spec in field_specs:
        if "." not in spec:
            fail("field spec '%s' needs the form <constructor>.<field>" % spec)
        ctor_name, field_name = spec.rsplit(".", 1)
        ctor = schema.constructors.get(ctor_name)
        if ctor is None:
            fail("field spec '%s': no constructor named '%s'" % (spec, ctor_name))
        index = None
        for i, p in enumerate(ctor["params"]):
            if p["name"] == field_name:
                index = i
                break
        if index is None:
            names = ", ".join(p["name"] for p in ctor["params"])
            fail("field spec '%s': constructor '%s' has no field '%s'. It has: %s"
                 % (spec, ctor_name, field_name, names))
        const = "F_" + java_name(ctor_name) + "__" + field_name.upper()
        out.append((const, index, spec, ctor["params"][index]["type"]))
    return out


def closure(schema, methods, extra_types):
    """Every constructor reachable from the whitelisted methods and types."""
    needed_types = set(extra_types)
    selected = {}
    pending = []

    for name in methods:
        entry = schema.methods.get(name)
        if entry is None:
            fail("method '%s' is not in the schema" % name)
        pending.append(entry)
        needed_types.add(entry["type"])

    seen_types = set()

    def want_type(type_name):
        if type_name is None or type_name in seen_types:
            return
        seen_types.add(type_name)
        for ctor in schema.by_type.get(type_name, []):
            if ctor["id"] not in selected:
                pending.append(ctor)

    for t in list(needed_types):
        want_type(t)

    while pending:
        entry = pending.pop()
        if entry["id"] in selected and entry.get("method") is None:
            continue
        if entry.get("method") is None:
            selected[entry["id"]] = entry
        for p in entry["params"]:
            _, _, ref = parse_type(p["type"])
            want_type(ref)

    return selected


def encode(schema, selected):
    """
    Encode every constructor as characters.

    Layout, all values as 16-bit chars:
        idHigh idLow fieldCount  then per field: kind, condition, [elementKind]

    condition is 0 for an unconditional field, otherwise
    (flagsSlot * 32 + bit) + 1 - see encode_condition.
    """
    chars = []
    index = {}

    for cid in sorted(selected):
        entry = selected[cid]
        index[cid] = len(chars)

        chars.append((cid >> 16) & 0xFFFF)
        chars.append(cid & 0xFFFF)

        fields = []
        for p in entry["params"]:
            kind, elem, _ = parse_type(p["type"])
            fields.append((kind, encode_condition(entry, p["type"]), elem))

        chars.append(len(fields))
        for kind, cond, elem in fields:
            chars.append(kind)
            chars.append(cond)
            if kind in (K_VECTOR, K_BARE_VECTOR):
                chars.append(elem)

    return chars, index


def java_string_literal(chars):
    """
    Encode the table as Base64 for embedding in Java source.

    Not \\uXXXX: those are processed by the Java *lexer* before string literals
    are parsed, so \\u000a inserts a real newline and terminates the line, and
    \\u0022 would close the string. Unicode escapes are a source-file feature,
    not a string feature - a classic way to generate Java that will not compile.

    Base64 over the big-endian byte form is unambiguous, uses only characters
    that are safe everywhere, and needs a decoder of about fifteen lines on the
    device.
    """
    raw = bytearray()
    for c in chars:
        raw.append((c >> 8) & 0xFF)
        raw.append(c & 0xFF)
    import base64
    return base64.b64encode(bytes(raw)).decode("ascii")


def emit(schema, selected, methods, fields):
    chars, index = encode(schema, selected)

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # --- the table ---------------------------------------------------------
    lines = []
    a = lines.append
    a("package tg.api;")
    a("")
    a("/**")
    a(" * TL schema table - GENERATED by tools/generate-tl.py, do not edit.")
    a(" *")
    a(" * Covers the transitive closure of config/tl-whitelist.txt: %d constructor(s)."
      % len(selected))
    a(" * Walked by tg.tl.TlParser; see that class for the field-kind encoding.")
    a(" *")
    a(" * Stored as a Base64 String rather than an int[] because a large array")
    a(" * initialiser compiles to one bytecode store per element, which is")
    a(" * enormous. A String lives in the constant pool and is decoded once at")
    a(" * class init.")
    a(" */")
    a("public final class TlSchema")
    a("{")
    a("    private TlSchema() { }")
    a("")

    # Split the literal: a single JVM string constant is limited to 65535 UTF-8
    # bytes, and Base64 is one byte per character.
    chunk_size = 40000
    b64 = java_string_literal(chars)
    parts = [b64[i:i + chunk_size] for i in range(0, len(b64), chunk_size)]

    a("    /** Base64 of the descriptor table, big-endian 16-bit values. */")
    a("    private static final String[] TABLE_PARTS = {")
    for pi, part in enumerate(parts):
        for i in range(0, len(part), 96):
            piece = part[i:i + 96]
            last_piece = i + 96 >= len(part)
            prefix = '        "' if i == 0 else '        + "'
            suffix = '"' if not last_piece else ('",' if pi < len(parts) - 1 else '"')
            a(prefix + piece + suffix)
    a("    };")
    a("")
    a("    private static final String B64 =")
    a("        \"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/\";")
    a("")

    a("    /** Constructor ids present in the table, ascending. */")
    a("    private static int[] ids;")
    a("    /** Offset of each constructor's descriptor within the decoded table. */")
    a("    private static int[] offsets;")
    a("    private static char[] table;")
    a("")
    a("    /** CLDC has no Base64 decoder, so here is one. */")
    a("    private static char[] decode(String[] parts)")
    a("    {")
    a("        int[] rev = new int[128];")
    a("        for (int i = 0; i < rev.length; i++) { rev[i] = -1; }")
    a("        for (int i = 0; i < B64.length(); i++) { rev[B64.charAt(i)] = i; }")
    a("")
    a("        int encoded = 0;")
    a("        for (int i = 0; i < parts.length; i++) { encoded += parts[i].length(); }")
    a("")
    a("        byte[] bytes = new byte[encoded / 4 * 3];")
    a("        int out = 0;")
    a("        int acc = 0;")
    a("        int bits = 0;")
    a("        for (int p = 0; p < parts.length; p++)")
    a("        {")
    a("            String s = parts[p];")
    a("            for (int i = 0; i < s.length(); i++)")
    a("            {")
    a("                char c = s.charAt(i);")
    a("                if (c == '=') { continue; }")
    a("                int v = rev[c];")
    a("                acc = (acc << 6) | v;")
    a("                bits += 6;")
    a("                if (bits >= 8)")
    a("                {")
    a("                    bits -= 8;")
    a("                    bytes[out++] = (byte) (acc >> bits);")
    a("                }")
    a("            }")
    a("        }")
    a("")
    a("        char[] t = new char[out / 2];")
    a("        for (int i = 0; i < t.length; i++)")
    a("        {")
    a("            t[i] = (char) (((bytes[i * 2] & 0xff) << 8) | (bytes[i * 2 + 1] & 0xff));")
    a("        }")
    a("        return t;")
    a("    }")
    a("")
    a("    private static synchronized void init()")
    a("    {")
    a("        if (table != null) { return; }")
    a("        char[] t = decode(TABLE_PARTS);")
    a("")
    a("        int count = %d;" % len(selected))
    a("        int[] idArr = new int[count];")
    a("        int[] offArr = new int[count];")
    a("        int pos = 0;")
    a("        for (int i = 0; i < count; i++)")
    a("        {")
    a("            offArr[i] = pos;")
    a("            idArr[i] = (t[pos] << 16) | t[pos + 1];")
    a("            int fields = t[pos + 2];")
    a("            pos += 3;")
    a("            for (int f = 0; f < fields; f++)")
    a("            {")
    a("                int kind = t[pos];")
    a("                pos += 2;")
    a("                if (kind == %d || kind == %d) { pos++; }" % (K_VECTOR, K_BARE_VECTOR))
    a("            }")
    a("        }")
    a("        ids = idArr;")
    a("        offsets = offArr;")
    a("        table = t;")
    a("    }")
    a("")
    a("    public static char[] table()")
    a("    {")
    a("        init();")
    a("        return table;")
    a("    }")
    a("")
    a("    /**")
    a("     * Offset of a constructor's descriptor, or -1 when the schema does not")
    a("     * cover it. Binary search over ids sorted at generation time.")
    a("     */")
    a("    public static int offsetOf(int constructorId)")
    a("    {")
    a("        init();")
    a("        int lo = 0;")
    a("        int hi = ids.length - 1;")
    a("        while (lo <= hi)")
    a("        {")
    a("            int mid = (lo + hi) >>> 1;")
    a("            // Unsigned comparison: constructor ids use the full 32 bits.")
    a("            int a = ids[mid] ^ 0x80000000;")
    a("            int b = constructorId ^ 0x80000000;")
    a("            if (a == b) { return offsets[mid]; }")
    a("            if (a < b) { lo = mid + 1; } else { hi = mid - 1; }")
    a("        }")
    a("        return -1;")
    a("    }")
    a("")
    a("    public static int constructorCount()")
    a("    {")
    a("        init();")
    a("        return ids.length;")
    a("    }")
    a("}")

    (OUT_DIR / "TlSchema.java").write_text("\n".join(lines) + "\n",
                                           encoding="utf-8", newline="\n")

    # --- method and constructor ids ---------------------------------------
    lines = []
    a = lines.append
    a("package tg.api;")
    a("")
    a("/**")
    a(" * Constructor and method ids - GENERATED by tools/generate-tl.py.")
    a(" *")
    a(" * Only the names the client actually references are emitted; the rest of")
    a(" * the closure is reachable through tg.api.TlSchema by id alone.")
    a(" */")
    a("public final class Api")
    a("{")
    a("    private Api() { }")
    a("")
    a("    /** Schema layer these ids came from. */")
    a("    public static final int LAYER = %d;" % detect_layer())
    a("")
    a("    // --- methods ---")
    for name in sorted(methods):
        entry = schema.methods[name]
        a("    public static final int %s = 0x%08x;"
          % (java_name(name), entry["id"]))
    a("")
    a("    // --- constructors ---")
    # TL names are unique within a schema but not across them: mtproto.json's
    # `message` (a container element) and the API's `message` both want to be
    # MESSAGE. The API name wins - it is the one application code uses - and the
    # MTProto one takes an MT_ prefix.
    used = {}
    for cid in selected:
        used.setdefault(java_name(selected[cid]["name"]), []).append(cid)

    for cid in sorted(selected, key=lambda c: selected[c]["name"]):
        entry = selected[cid]
        name = java_name(entry["name"])
        if len(used[name]) > 1:
            if entry.get("origin") == "mt":
                name = "MT_" + name
            elif entry.get("origin") != "api":
                name = "%s_%08X" % (name, cid)
        a("    public static final int %s = 0x%08x;" % (name, cid))
    a("")
    a("    // --- field indices ---")
    a("    // Positions within the constructor, for TlObj accessors. Generated")
    a("    // from config/tl-whitelist.txt [fields]; a schema change that drops or")
    a("    // reorders one of these fails generation rather than misreading data.")
    for const, index, spec, tl_type in fields:
        a("    /** %s : %s */" % (spec, tl_type))
        a("    public static final int %s = %d;" % (const, index))
    a("}")

    (OUT_DIR / "Api.java").write_text("\n".join(lines) + "\n",
                                      encoding="utf-8", newline="\n")

    return len(chars)


def detect_layer():
    """The layer is not in the JSON; it is pinned in tg.mt.Layer."""
    layer_java = REPO / "src" / "tg" / "mt" / "Layer.java"
    m = re.search(r"LAYER\s*=\s*(\d+)", layer_java.read_text(encoding="utf-8"))
    return int(m.group(1)) if m else 0


def java_name(tl_name):
    """messages.getDialogs -> MESSAGES_GET_DIALOGS"""
    out = []
    for ch in tl_name.replace(".", "_"):
        if ch.isupper() and out and out[-1] != "_":
            out.append("_")
        out.append(ch.upper())
    name = "".join(out)
    return re.sub(r"_+", "_", name)


def main():
    schema = Schema()
    methods, extra_types, field_specs = read_whitelist()
    selected = closure(schema, methods, extra_types)
    fields = resolve_fields(schema, field_specs)
    chars = emit(schema, selected, methods, fields)

    print("generate-tl: %d method(s) whitelisted" % len(methods))
    print("generate-tl: %d constructor(s) in the closure (of %d in the schema)"
          % (len(selected), len(schema.constructors)))
    print("generate-tl: descriptor table is %d chars" % chars)
    print("generate-tl: wrote generated/tg/api/{TlSchema,Api}.java")
    return 0


if __name__ == "__main__":
    sys.exit(main())
