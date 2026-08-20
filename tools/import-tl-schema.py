#!/usr/bin/env python3
"""Convert a pinned Telegram ``.tl`` API schema to the JSON generator input.

The converter is deliberately offline.  ``schema/api.tl`` is a reviewed,
committed input; ordinary builds must never follow Telegram's moving schema.

Usage:
    python tools/import-tl-schema.py schema/api.tl schema/api.json
    python tools/import-tl-schema.py --check schema/api.tl schema/api.json
"""

import argparse
import json
import re
import sys
from pathlib import Path


DECLARATION = re.compile(
    r"^([A-Za-z0-9_.]+)#([0-9a-fA-F]+)\s*(.*?)\s*=\s*([^;]+);$")
BUILTIN = re.compile(
    r"^[A-Za-z0-9_]+(?:\s+\?)?\s*=\s*[A-Za-z0-9_]+;$")


class SchemaError(ValueError):
    pass


def split_params(text):
    """Split TL parameters on top-level whitespace."""
    out = []
    current = []
    depth = 0
    opening = "<{[("
    closing = ">}])"

    for char in text:
        if char.isspace() and depth == 0:
            if current:
                out.append("".join(current))
                current = []
            continue
        current.append(char)
        if char in opening:
            depth += 1
        elif char in closing:
            depth -= 1
            if depth < 0:
                raise SchemaError("unbalanced parameter delimiters")

    if depth != 0:
        raise SchemaError("unbalanced parameter delimiters")
    if current:
        out.append("".join(current))
    return out


def signed_id(hex_id):
    value = int(hex_id, 16)
    if value > 0xFFFFFFFF:
        raise SchemaError("constructor id does not fit 32 bits: %s" % hex_id)
    return value - 0x100000000 if value >= 0x80000000 else value


def parse_tl(text):
    result = {"constructors": [], "methods": []}
    section = "constructors"
    names = {"constructors": set(), "methods": set()}
    ids = {"constructors": set(), "methods": set()}

    for line_number, raw in enumerate(text.splitlines(), 1):
        line = raw.split("//", 1)[0].strip()
        if not line:
            continue
        if line == "---types---":
            section = "constructors"
            continue
        if line == "---functions---":
            section = "methods"
            continue

        match = DECLARATION.match(line)
        if match is None:
            # Primitive aliases (``int ? = Int;`` and ``bytes = Bytes;``) are
            # TL grammar, but are not entries in Telegram's JSON schema.
            if BUILTIN.match(line):
                continue
            raise SchemaError("line %d is not a TL declaration: %s"
                              % (line_number, line))

        name, hex_id, body, result_type = match.groups()
        constructor_id = signed_id(hex_id)
        if name in names[section]:
            raise SchemaError("line %d duplicates %s" % (line_number, name))
        if constructor_id in ids[section]:
            raise SchemaError("line %d duplicates id %s" % (line_number, hex_id))
        names[section].add(name)
        ids[section].add(constructor_id)

        params = []
        # The generic vector declaration uses ``{t:Type} # [ t ]``.  The
        # public JSON schema represents it as a constructor with no fields.
        if name != "vector":
            for token in split_params(body):
                if token.startswith("{") and token.endswith("}"):
                    continue  # Generic type parameter, e.g. {X:Type}.
                if ":" not in token:
                    raise SchemaError("line %d has malformed parameter %s"
                                      % (line_number, token))
                param_name, param_type = token.split(":", 1)
                if not param_name or not param_type:
                    raise SchemaError("line %d has malformed parameter %s"
                                      % (line_number, token))
                params.append({"name": param_name, "type": param_type})

        name_key = "predicate" if section == "constructors" else "method"
        result[section].append({
            "id": str(constructor_id),
            name_key: name,
            "params": params,
            "type": result_type.strip(),
        })

    if not result["constructors"] or not result["methods"]:
        raise SchemaError("schema must contain both constructors and methods")
    return result


def render_tl(text):
    return json.dumps(parse_tl(text), ensure_ascii=False,
                      separators=(",", ":")).encode("utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify OUTPUT instead of rewriting it")
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args(argv)

    try:
        rendered = render_tl(args.input.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, SchemaError) as exc:
        print("import-tl-schema: ERROR: %s" % exc, file=sys.stderr)
        return 2

    if args.check:
        try:
            existing = args.output.read_bytes()
        except OSError as exc:
            print("import-tl-schema: ERROR: %s" % exc, file=sys.stderr)
            return 2
        if existing != rendered:
            print("import-tl-schema: %s is not generated from %s"
                  % (args.output, args.input), file=sys.stderr)
            return 1
        print("import-tl-schema: OK - %s reproduces %s"
              % (args.input, args.output))
        return 0

    try:
        args.output.write_bytes(rendered)
    except OSError as exc:
        print("import-tl-schema: ERROR: %s" % exc, file=sys.stderr)
        return 2
    schema = json.loads(rendered.decode("utf-8"))
    print("import-tl-schema: wrote %d constructors and %d methods to %s"
          % (len(schema["constructors"]), len(schema["methods"]), args.output))
    return 0


if __name__ == "__main__":
    sys.exit(main())
