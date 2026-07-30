#!/usr/bin/env python3
"""
Enforce the CLDC 1.1 / MIDP 2.0 API subset on compiled device classes.

Why this exists
---------------
When Sun WTK is installed the build passes the real cldcapi11.jar +
midpapi20.jar as -bootclasspath and javac itself rejects anything outside the
subset. Without WTK we have to fall back to JDK 8's rt.jar, which is a huge
superset: `new StringBuilder()`, `System.nanoTime()`, autoboxing or an
`ArrayList` would compile happily on the desktop and then fail to verify - or
throw NoClassDefFoundError - on the handset.

This script closes that hole. It reads the constant pool of every compiled
class and fails the build on:

  * a reference to a class outside config/cldc11-midp20-api.txt
  * a reference to a member on the [deny-members] list (members that exist in
    J2SE but not in CLDC 1.1 - System.nanoTime, Integer.valueOf(int) emitted by
    autoboxing, Vector.add, Math.pow, ...)
  * a class file whose major version is above the CLDC ceiling

Usage:
    python tools/check-api.py build/device/classes [--allow-list config/...txt]
"""

import struct
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_ALLOW = REPO / "config" / "cldc11-midp20-api.txt"

# CLDC class files are Java 1.1 format (45.3). Some KVMs tolerate up to 47.0,
# but nothing above it. Anything higher is a build misconfiguration.
MAX_MAJOR = 47

# Constant pool tags -> (payload size, or None for variable)
TAG_UTF8 = 1
TAG_INTEGER = 3
TAG_FLOAT = 4
TAG_LONG = 5
TAG_DOUBLE = 6
TAG_CLASS = 7
TAG_STRING = 8
TAG_FIELDREF = 9
TAG_METHODREF = 10
TAG_IFACEMETHODREF = 11
TAG_NAMEANDTYPE = 12
TAG_METHODHANDLE = 15
TAG_METHODTYPE = 16
TAG_DYNAMIC = 17
TAG_INVOKEDYNAMIC = 18
TAG_MODULE = 19
TAG_PACKAGE = 20

FIXED_SIZE = {
    TAG_INTEGER: 4, TAG_FLOAT: 4, TAG_LONG: 8, TAG_DOUBLE: 8,
    TAG_CLASS: 2, TAG_STRING: 2, TAG_FIELDREF: 4, TAG_METHODREF: 4,
    TAG_IFACEMETHODREF: 4, TAG_NAMEANDTYPE: 4, TAG_METHODHANDLE: 3,
    TAG_METHODTYPE: 2, TAG_DYNAMIC: 4, TAG_INVOKEDYNAMIC: 4,
    TAG_MODULE: 2, TAG_PACKAGE: 2,
}


class ClassFile(object):
    """Just enough class-file parsing to enumerate external API references."""

    def __init__(self, data):
        self.data = data
        self.pos = 0
        self.pool = {}          # index -> (tag, payload)
        self.major = 0
        self.this_class = None
        self.class_refs = set()          # internal names, e.g. java/lang/String
        self.member_refs = set()         # (owner, name, descriptor)
        self.descriptors = set()         # every field/method descriptor seen
        self._parse()

    # -- primitive readers -------------------------------------------------
    def u1(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def u2(self):
        v = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return v

    def u4(self):
        v = struct.unpack_from(">I", self.data, self.pos)[0]
        self.pos += 4
        return v

    def skip(self, n):
        self.pos += n

    # -- structure ---------------------------------------------------------
    def _parse(self):
        if self.u4() != 0xCAFEBABE:
            raise ValueError("not a class file")
        self.u2()                        # minor
        self.major = self.u2()
        self._parse_pool()
        self.u2()                        # access_flags
        self.this_class = self._class_name(self.u2())
        self.u2()                        # super_class
        for _ in range(self.u2()):       # interfaces
            self.u2()
        self._parse_members()            # fields
        self._parse_members()            # methods
        self._collect_refs()

    def _parse_pool(self):
        count = self.u2()
        i = 1
        while i < count:
            tag = self.u1()
            if tag == TAG_UTF8:
                length = self.u2()
                self.pool[i] = (tag, self.data[self.pos:self.pos + length])
                self.skip(length)
            else:
                size = FIXED_SIZE.get(tag)
                if size is None:
                    raise ValueError("unknown constant pool tag %d" % tag)
                self.pool[i] = (tag, self.data[self.pos:self.pos + size])
                self.skip(size)
            # long/double occupy two pool slots
            i += 2 if tag in (TAG_LONG, TAG_DOUBLE) else 1

    def _parse_members(self):
        for _ in range(self.u2()):
            self.u2()                    # access_flags
            self.u2()                    # name_index
            self.descriptors.add(self._utf8(self.u2()))
            self._skip_attributes()

    def _skip_attributes(self):
        for _ in range(self.u2()):
            self.u2()                    # attribute_name_index
            self.skip(self.u4())

    # -- pool accessors ----------------------------------------------------
    def _utf8(self, idx):
        tag, payload = self.pool[idx]
        if tag != TAG_UTF8:
            raise ValueError("index %d is not a Utf8" % idx)
        return payload.decode("utf-8", "replace")

    def _class_name(self, idx):
        tag, payload = self.pool[idx]
        if tag != TAG_CLASS:
            raise ValueError("index %d is not a Class" % idx)
        return self._utf8(struct.unpack(">H", payload)[0])

    def _collect_refs(self):
        for idx, (tag, payload) in self.pool.items():
            if tag == TAG_CLASS:
                self.class_refs.add(self._utf8(struct.unpack(">H", payload)[0]))
            elif tag in (TAG_FIELDREF, TAG_METHODREF, TAG_IFACEMETHODREF):
                cls_idx, nat_idx = struct.unpack(">HH", payload)
                owner = self._class_name(cls_idx)
                _, nat = self.pool[nat_idx]
                name_idx, desc_idx = struct.unpack(">HH", nat)
                name = self._utf8(name_idx)
                desc = self._utf8(desc_idx)
                self.member_refs.add((owner, name, desc))
                self.descriptors.add(desc)
            elif tag == TAG_NAMEANDTYPE:
                _, desc_idx = struct.unpack(">HH", payload)
                self.descriptors.add(self._utf8(desc_idx))


def types_in_descriptor(desc):
    """Extract every object type named by a field or method descriptor."""
    out = set()
    i = 0
    while i < len(desc):
        if desc[i] == "L":
            end = desc.find(";", i)
            if end < 0:
                break
            out.add(desc[i + 1:end])
            i = end + 1
        else:
            i += 1
    return out


def normalize(name):
    """
    Turn a CONSTANT_Class name into a plain internal class name.

    A CONSTANT_Class holds either an internal name (java/lang/String) or, for
    array types, a descriptor ([I, [[Ljava/lang/String;). Primitive arrays
    normalize to "" so the caller skips them.
    """
    if not name.startswith("["):
        return name
    while name.startswith("["):
        name = name[1:]
    if name.startswith("L") and name.endswith(";"):
        return name[1:-1]
    return ""                       # [I, [[J, ... - no class involved


def load_allow_list(path):
    allowed_classes = set()
    denied_members = set()
    section = None
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            continue
        if section == "allow-classes":
            allowed_classes.add(line.replace(".", "/"))
        elif section == "deny-members":
            denied_members.add(line)
    return allowed_classes, denied_members


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 2
    root = Path(args[0])
    allow_path = Path(args[1]) if len(args) > 1 else DEFAULT_ALLOW
    if not root.exists():
        print("check-api: no such directory: %s" % root, file=sys.stderr)
        return 2

    allowed_classes, denied_members = load_allow_list(allow_path)

    class_files = sorted(root.rglob("*.class"))
    if not class_files:
        print("check-api: no .class files under %s" % root, file=sys.stderr)
        return 2

    parsed = []
    own = set()
    for cf in class_files:
        try:
            c = ClassFile(cf.read_bytes())
        except Exception as exc:                       # noqa: BLE001
            print("check-api: failed to parse %s: %s" % (cf, exc), file=sys.stderr)
            return 2
        parsed.append((cf, c))
        own.add(c.this_class)

    violations = []          # (our class, kind, detail)

    for cf, c in parsed:
        if c.major > MAX_MAJOR:
            violations.append((c.this_class, "classfile-version",
                               "major=%d exceeds CLDC ceiling %d "
                               "(build must use -target 1.1)" % (c.major, MAX_MAJOR)))

        referenced = set()
        for name in c.class_refs:
            referenced.add(normalize(name))
        for desc in c.descriptors:
            for t in types_in_descriptor(desc):
                referenced.add(normalize(t))

        for name in sorted(referenced):
            if not name or name in own or name in allowed_classes:
                continue
            # anonymous/inner classes of our own code
            if name.split("$", 1)[0] in own:
                continue
            violations.append((c.this_class, "class", name.replace("/", ".")))

        for owner, mname, desc in sorted(c.member_refs):
            owner = normalize(owner)
            if owner in own:
                continue
            for key in ("%s#%s" % (owner, mname), "%s#%s:%s" % (owner, mname, desc)):
                if key in denied_members:
                    violations.append(
                        (c.this_class, "member",
                         "%s.%s%s  - not present in CLDC 1.1"
                         % (owner.replace("/", "."), mname, desc)))
                    break

    print("check-api: scanned %d class(es) under %s" % (len(parsed), root))
    if not violations:
        print("check-api: OK - CLDC 1.1 / MIDP 2.0 subset respected")
        return 0

    print("")
    print("check-api: %d violation(s)" % len(violations), file=sys.stderr)
    by_class = {}
    for owner, kind, detail in violations:
        by_class.setdefault(owner, []).append((kind, detail))
    for owner in sorted(by_class):
        print("  %s" % owner.replace("/", "."), file=sys.stderr)
        for kind, detail in sorted(set(by_class[owner])):
            print("      [%s] %s" % (kind, detail), file=sys.stderr)
    print("", file=sys.stderr)
    print("  A class listed above either does not exist in CLDC 1.1 / MIDP 2.0,", file=sys.stderr)
    print("  or is a legitimate API missing from config/cldc11-midp20-api.txt.", file=sys.stderr)
    print("  Verify against the JSR 139 / JSR 118 specs before widening the list.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
