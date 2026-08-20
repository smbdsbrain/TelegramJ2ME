#!/usr/bin/env python3
"""
Report whether Telegram's current layer and schema still match the pinned copy.

Why this exists
---------------
schema/api.json is derived offline from the reviewed schema/api.tl source,
schema/mtproto.json remains a verbatim public download, and src/tg/mt/Layer.java
pins the layer number.  The public API JSON endpoint may lag the server's layer;
the provenance record names the one exact lagging hash that is acceptable.

This script is the check that would otherwise depend on a maintainer
remembering to look. It is deliberately *not* part of the build:

  * ordinary builds and pull-request CI stay pinned, reproducible and offline
    with respect to Telegram, so a clone built today and the same clone built
    next year produce the same JAR;
  * only .github/workflows/schema-drift.yml runs it with --online, on a
    schedule and on demand;
  * it never rewrites schema/, Layer.java or UPSTREAM.md. Raising the layer
    stays a reviewed commit with a regeneration, a size measurement and live
    testing.

Offline it still has work to do: it checks the pinned files against the hashes
and counts recorded in schema/UPSTREAM.md, which catches a hand-edited or
half-updated schema without any network at all.

Statuses, in order of precedence, are also the exit codes:

    0   ok             pinned record consistent; upstream matches (or was not
                       consulted, in --offline mode)
    10  drift          upstream layer and/or schema differ from the pinned copy
    20  unavailable    upstream could not be reached - explicitly NOT drift
    30  malformed      upstream answered with something unparseable
    40  pinned-mismatch  the repository disagrees with itself, no network needed

Usage:
    python tools/check-schema-drift.py                 # offline, pinned record only
    python tools/check-schema-drift.py --online
    python tools/check-schema-drift.py --online --json drift.json --summary out.md
"""

import argparse
import hashlib
import importlib.util
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

CONFIG_URL = "https://core.telegram.org/api/config.json"
API_URL = "https://core.telegram.org/schema/json"
MTPROTO_URL = "https://core.telegram.org/schema/mtproto-json"

# Only the official endpoints. A mirror can be a layer behind or ahead, and a
# monitor that trusts one turns someone else's staleness into our alarm.
SCHEMA_URLS = {"api.json": API_URL, "mtproto.json": MTPROTO_URL}

STATUS_OK = "ok"
STATUS_DRIFT = "drift"
STATUS_UNAVAILABLE = "unavailable"
STATUS_MALFORMED = "malformed"
STATUS_PINNED_MISMATCH = "pinned-mismatch"

EXIT_CODES = {
    STATUS_OK: 0,
    STATUS_DRIFT: 10,
    STATUS_UNAVAILABLE: 20,
    STATUS_MALFORMED: 30,
    STATUS_PINNED_MISMATCH: 40,
}

# Worst-first: one run can hit several of these at once and the caller needs a
# single answer. A repository that disagrees with itself outranks anything
# upstream might say, because until it is fixed we do not know what is pinned.
PRECEDENCE = [
    STATUS_PINNED_MISMATCH,
    STATUS_MALFORMED,
    STATUS_UNAVAILABLE,
    STATUS_DRIFT,
    STATUS_OK,
]

USER_AGENT = "TelegramJ2ME-schema-drift-monitor (+https://github.com/smbdsbrain/TelegramJ2ME)"

# api.json is ~2 MB. Ten is room for years of growth and still a bound.
MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024


class Unavailable(Exception):
    """Upstream could not be reached. Not evidence about the protocol."""


class Malformed(Exception):
    """Upstream was reached and answered with something we cannot read."""


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def load_tl_importer(repo):
    path = repo / "tools" / "import-tl-schema.py"
    if not path.exists():
        raise Malformed("%s is missing" % path)
    try:
        spec = importlib.util.spec_from_file_location("import_tl_schema", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    except Exception as exc:
        raise Malformed("cannot load %s: %s" % (path, exc))


def read_pinned(repo=REPO):
    """The layer, schema hashes and counts this repository currently ships."""
    layer_java = repo / "src" / "tg" / "mt" / "Layer.java"
    text = layer_java.read_text(encoding="utf-8")
    m = re.search(r"LAYER\s*=\s*(\d+)", text)
    if not m:
        raise Malformed("no LAYER constant in %s" % layer_java)

    pinned = {"layer": int(m.group(1)), "schemas": {}, "api_source": None}
    for name in SCHEMA_URLS:
        path = repo / "schema" / name
        data = path.read_bytes()
        counts = count_schema(data, name)
        pinned["schemas"][name] = {
            "sha256": sha256(data),
            "bytes": len(data),
            "constructors": counts[0],
            "methods": counts[1],
        }

    source_path = repo / "schema" / "api.tl"
    if source_path.exists():
        try:
            source_data = source_path.read_bytes()
            source_text = source_data.decode("utf-8")
            rendered = load_tl_importer(repo).render_tl(source_text)
        except (OSError, UnicodeError, ValueError) as exc:
            raise Malformed("cannot read pinned api.tl: %s" % exc)
        counts = count_schema(rendered, "api.tl conversion")
        marker = re.search(r"^//\s*LAYER\s+(\d+)\s*$", source_text,
                           re.MULTILINE)
        pinned["api_source"] = {
            "sha256": sha256(source_data),
            "bytes": len(source_data),
            "constructors": counts[0],
            "methods": counts[1],
            "layer": int(marker.group(1)) if marker else None,
            "derived_sha256": sha256(rendered),
            "matches_api_json": rendered == (repo / "schema" / "api.json").read_bytes(),
        }
    return pinned


def count_schema(data, what="schema"):
    """(constructors, methods) in a TL schema JSON document."""
    try:
        doc = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise Malformed("%s is not JSON: %s" % (what, exc))
    if not isinstance(doc, dict):
        raise Malformed("%s is not a JSON object" % what)
    constructors = doc.get("constructors")
    methods = doc.get("methods")
    if not isinstance(constructors, list) or not isinstance(methods, list):
        raise Malformed("%s has no constructors/methods arrays" % what)
    return len(constructors), len(methods)


def parse_config_layer(data):
    """The current layer, from the official machine-readable /api/config.json.

    Read from JSON on purpose. The layer is also written in prose on
    core.telegram.org, and scraping that is how a monitor ends up reporting a
    version number out of a changelog sentence.
    """
    try:
        doc = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise Malformed("config.json is not JSON: %s" % exc)
    if not isinstance(doc, dict):
        raise Malformed("config.json is not a JSON object")
    if "layer" not in doc:
        raise Malformed("config.json has no 'layer' field")
    layer = doc["layer"]
    # Guard the shape as well as the presence: `true` and `"225"` both survive
    # a bare truthiness check and would be reported as a layer number.
    if isinstance(layer, bool) or not isinstance(layer, int) or layer <= 0:
        raise Malformed("config.json layer is not a positive integer: %r" % (layer,))
    return layer


def parse_upstream_record(text):
    """What schema/UPSTREAM.md claims is pinned.

    The document is the human-readable record of the same facts read_pinned()
    measures from the files. Parsing it is what makes a half-finished schema
    refresh - files replaced, table not updated, or the reverse - visible
    without a network round trip.
    """
    record = {
        "layer": None,
        "schemas": {},
        "api_source": {},
        "accepted_live_sha256": {},
    }

    m = re.search(r"^##\s+Layer\s+(\d+)\s*$", text, re.MULTILINE)
    if m:
        record["layer"] = int(m.group(1))

    columns = []
    for line in text.splitlines():
        if not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if not cells:
            continue
        label = cells[0].strip("` ").lower()

        if not columns:
            # The header row names the files: | | `api.json` | `mtproto.json` |
            named = [c.strip("` ") for c in cells[1:]]
            if all(n in SCHEMA_URLS for n in named) and named:
                columns = named
            continue

        values = cells[1:]
        if len(values) != len(columns):
            continue

        if label == "sha-256":
            for name, value in zip(columns, values):
                record["schemas"].setdefault(name, {})["sha256"] = value.strip("` ").lower()
        elif label == "contents":
            for name, value in zip(columns, values):
                cm = re.search(r"(\d+)\s+constructors?,\s*(\d+)\s+methods?", value)
                if cm:
                    entry = record["schemas"].setdefault(name, {})
                    entry["constructors"] = int(cm.group(1))
                    entry["methods"] = int(cm.group(2))

    source_hash = re.search(
        r"^Pinned API TL SHA-256:\s*`([0-9a-fA-F]{64})`\s*$",
        text, re.MULTILINE)
    source_commit = re.search(
        r"^Pinned API TL commit:\s*`([0-9a-fA-F]{40})`\s*$",
        text, re.MULTILINE)
    source_contents = re.search(
        r"^Pinned API TL contents:\s*(\d+)\s+constructors?,\s*"
        r"(\d+)\s+methods?\s*$", text, re.MULTILINE)
    lag_hash = re.search(
        r"^Known lagging API endpoint SHA-256:\s*`([0-9a-fA-F]{64})`\s*$",
        text, re.MULTILINE)
    if source_hash:
        record["api_source"]["sha256"] = source_hash.group(1).lower()
    if source_commit:
        record["api_source"]["commit"] = source_commit.group(1).lower()
    if source_contents:
        record["api_source"]["constructors"] = int(source_contents.group(1))
        record["api_source"]["methods"] = int(source_contents.group(2))
    if lag_hash:
        record["accepted_live_sha256"]["api.json"] = lag_hash.group(1).lower()

    return record


def check_pinned_record(pinned, record):
    """Problems inside the repository itself. No network involved."""
    problems = []

    if record["layer"] is None:
        problems.append("UPSTREAM.md has no '## Layer <n>' heading to check Layer.java against")
    elif record["layer"] != pinned["layer"]:
        problems.append("Layer.java pins layer %d but UPSTREAM.md documents layer %d"
                        % (pinned["layer"], record["layer"]))

    for name in sorted(SCHEMA_URLS):
        actual = pinned["schemas"][name]
        claimed = record["schemas"].get(name)
        if not claimed:
            problems.append("UPSTREAM.md records nothing about %s" % name)
            continue
        if "sha256" not in claimed:
            problems.append("UPSTREAM.md records no SHA-256 for %s" % name)
        elif claimed["sha256"] != actual["sha256"]:
            problems.append("%s hashes %s but UPSTREAM.md records %s"
                            % (name, actual["sha256"], claimed["sha256"]))
        if "constructors" in claimed and claimed["constructors"] != actual["constructors"]:
            problems.append("%s has %d constructors but UPSTREAM.md records %d"
                            % (name, actual["constructors"], claimed["constructors"]))
        if "methods" in claimed and claimed["methods"] != actual["methods"]:
            problems.append("%s has %d methods but UPSTREAM.md records %d"
                            % (name, actual["methods"], claimed["methods"]))

    source = pinned.get("api_source")
    if source is not None:
        claimed = record.get("api_source", {})
        if not claimed:
            problems.append("UPSTREAM.md records nothing about api.tl")
        elif claimed.get("sha256") != source["sha256"]:
            problems.append("api.tl hashes %s but UPSTREAM.md records %s"
                            % (source["sha256"], claimed.get("sha256", "nothing")))
        if claimed.get("constructors") != source["constructors"]:
            problems.append("api.tl has %d constructors but UPSTREAM.md records %s"
                            % (source["constructors"],
                               claimed.get("constructors", "nothing")))
        if claimed.get("methods") != source["methods"]:
            problems.append("api.tl has %d methods but UPSTREAM.md records %s"
                            % (source["methods"], claimed.get("methods", "nothing")))
        if not claimed.get("commit"):
            problems.append("UPSTREAM.md records no full Telegram Desktop commit for api.tl")
        if source["layer"] != pinned["layer"]:
            problems.append("api.tl marks layer %s but Layer.java pins layer %d"
                            % (source["layer"], pinned["layer"]))
        if not source["matches_api_json"]:
            problems.append("api.json is not the deterministic conversion of api.tl")
        lag = record.get("accepted_live_sha256", {}).get("api.json")
        if not lag:
            problems.append("UPSTREAM.md records no known lagging API endpoint SHA-256")

    return problems


def urllib_fetch(url, timeout=30):
    """Fetch bytes, mapping every transport failure to Unavailable.

    The distinction this preserves is the whole point of the exercise: a proxy
    refusing us, DNS failing or Telegram returning 502 says nothing whatsoever
    about the protocol, and must never be reported as drift.
    """
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = getattr(response, "status", None) or response.getcode()
            if status != 200:
                raise Unavailable("%s returned HTTP %s" % (url, status))
            chunks = []
            read = 0
            while read <= MAX_DOWNLOAD_BYTES:
                chunk = response.read(64 * 1024)
                if not chunk:
                    break
                chunks.append(chunk)
                read += len(chunk)
            data = b"".join(chunks)
    except urllib.error.HTTPError as exc:
        raise Unavailable("%s returned HTTP %s" % (url, exc.code))
    except urllib.error.URLError as exc:
        raise Unavailable("%s is unreachable: %s" % (url, exc.reason))
    except OSError as exc:
        raise Unavailable("%s is unreachable: %s" % (url, exc))

    if len(data) > MAX_DOWNLOAD_BYTES:
        raise Unavailable("%s served more than %d bytes" % (url, MAX_DOWNLOAD_BYTES))
    return data


def check(repo=REPO, fetch=None, online=False):
    """Produce the whole report. `fetch` is (url) -> bytes; tests substitute it."""
    report = {
        "status": STATUS_OK,
        "online": bool(online),
        "pinned": None,
        "live": None,
        "problems": [],
        "findings": [],
        "next_steps": [],
    }

    try:
        pinned = read_pinned(repo)
    except Malformed as exc:
        report["status"] = STATUS_PINNED_MISMATCH
        report["problems"].append(str(exc))
        return report
    report["pinned"] = pinned

    upstream_md = (repo / "schema" / "UPSTREAM.md").read_text(encoding="utf-8")
    record = parse_upstream_record(upstream_md)
    record_problems = check_pinned_record(pinned, record)
    if record_problems:
        report["problems"].extend(record_problems)
        report["status"] = STATUS_PINNED_MISMATCH
        report["next_steps"].append(
            "Reconcile schema/UPSTREAM.md with the files and src/tg/mt/Layer.java. "
            "Until they agree, what this build pins is not documented anywhere.")

    if not online:
        return report

    if fetch is None:
        fetch = urllib_fetch

    live = {"layer": None, "schemas": {}}
    report["live"] = live
    statuses = [report["status"]]

    try:
        live["layer"] = parse_config_layer(fetch(CONFIG_URL))
    except Unavailable as exc:
        statuses.append(STATUS_UNAVAILABLE)
        report["problems"].append("layer check unavailable: %s" % exc)
    except Malformed as exc:
        statuses.append(STATUS_MALFORMED)
        report["problems"].append("layer check malformed: %s" % exc)

    for name in sorted(SCHEMA_URLS):
        try:
            data = fetch(SCHEMA_URLS[name])
            constructors, methods = count_schema(data, name)
            live["schemas"][name] = {
                "sha256": sha256(data),
                "bytes": len(data),
                "constructors": constructors,
                "methods": methods,
            }
        except Unavailable as exc:
            statuses.append(STATUS_UNAVAILABLE)
            report["problems"].append("%s check unavailable: %s" % (name, exc))
        except Malformed as exc:
            statuses.append(STATUS_MALFORMED)
            report["problems"].append("%s check malformed: %s" % (name, exc))

    layer_moved = live["layer"] is not None and live["layer"] != pinned["layer"]
    known_lag = record.get("accepted_live_sha256", {}).get("api.json")
    api_live = live["schemas"].get("api.json")
    api_hash_is_known_lag = bool(
        api_live and known_lag and api_live["sha256"] == known_lag)
    # When config.json is unavailable, the exact documented old hash is not
    # evidence of drift either: deciding whether its allowance still applies
    # requires the layer answer that is missing. STATUS_UNAVAILABLE wins.
    api_is_known_lag = bool(
        api_hash_is_known_lag
        and (live["layer"] is None or live["layer"] == pinned["layer"]))
    schemas_moved = sorted(
        name for name, entry in live["schemas"].items()
        if entry["sha256"] != pinned["schemas"][name]["sha256"]
        and not (name == "api.json" and api_is_known_lag))

    if layer_moved:
        report["findings"].append(
            "Layer moved: pinned %d, official current %d." % (pinned["layer"], live["layer"]))
    if schemas_moved:
        for name in schemas_moved:
            report["findings"].append(
                "%s changed: pinned %s, current %s."
                % (name, pinned["schemas"][name]["sha256"], live["schemas"][name]["sha256"]))
    if api_is_known_lag and live["layer"] == pinned["layer"]:
        report["findings"].append(
            "api.json endpoint still serves the documented older schema %s; "
            "production config and the pinned reviewed TL source are both layer %d."
            % (known_lag, pinned["layer"]))

    if layer_moved or schemas_moved:
        statuses.append(STATUS_DRIFT)
        report["next_steps"].extend(drift_next_steps(layer_moved, schemas_moved))
    elif (live["layer"] is not None
          and len(live["schemas"]) == len(SCHEMA_URLS)
          and not api_is_known_lag):
        report["findings"].append(
            "Layer %d and both public schema files match the pinned copy."
            % pinned["layer"])

    report["status"] = worst(statuses)
    return report


def drift_next_steps(layer_moved, schemas_moved):
    """What a human does next. Nothing here is automated on purpose."""
    if layer_moved and schemas_moved:
        steps = ["Both the layer and the schema moved - a full layer upgrade."]
    elif schemas_moved:
        steps = ["The schema files changed while the layer number did not: usually an "
                 "in-place correction to the current layer, occasionally the first half "
                 "of a layer bump. Diff before assuming."]
    else:
        steps = ["The production layer moved while the public schema files did not. "
                 "Do not raise Layer.LAYER alone: pin and review the final official "
                 "Telegram Desktop TL source for the exact production layer, or wait "
                 "for the public JSON endpoint to catch up."]

    steps.append(
        "On a branch: update the pinned source and schema/UPSTREAM.md together "
        "with Layer.LAYER, regenerate, run tools/verify-tl-ids.py, measure both "
        "JAR variants and test against a live account before merging.")
    steps.append(
        "Nothing is upgraded automatically: a new layer can change constructor "
        "ids of types already parsed, so it is a reviewed commit or it is a "
        "silent wire-format break on a handset with no debugger.")
    return steps


def worst(statuses):
    for status in PRECEDENCE:
        if status in statuses:
            return status
    return STATUS_OK


def format_summary(report):
    """A GitHub job summary: the numbers and what to do, never a schema diff."""
    status = report["status"]
    headline = {
        STATUS_OK: "No drift",
        STATUS_DRIFT: "Schema drift detected",
        STATUS_UNAVAILABLE: "Check unavailable (this is not drift)",
        STATUS_MALFORMED: "Upstream response unreadable",
        STATUS_PINNED_MISMATCH: "The pinned record disagrees with itself",
    }[status]

    lines = ["## Telegram schema drift: %s" % headline, ""]

    pinned = report["pinned"]
    live = report["live"] or {"layer": None, "schemas": {}}
    if pinned:
        lines.append("| | Pinned | Official current |")
        lines.append("|---|---|---|")
        lines.append("| Layer | %d | %s |" % (
            pinned["layer"],
            live["layer"] if live["layer"] is not None else ("not checked" if not report["online"] else "unavailable")))
        for name in sorted(pinned["schemas"]):
            live_entry = live["schemas"].get(name)
            lines.append("| `%s` | `%s` | %s |" % (
                name,
                pinned["schemas"][name]["sha256"],
                "`%s`" % live_entry["sha256"] if live_entry
                else ("not checked" if not report["online"] else "unavailable")))
        lines.append("")

    for section, items in (("Findings", report["findings"]),
                           ("Problems", report["problems"]),
                           ("Next steps", report["next_steps"])):
        if items:
            lines.append("### %s" % section)
            lines.append("")
            for item in items:
                lines.append("- %s" % item)
            lines.append("")

    if not report["online"]:
        lines.append("_Offline run: only the repository's internal consistency was "
                     "checked. Pass `--online` to consult Telegram._")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def format_issue_body(report):
    """The deduplicated drift issue. Stable text so an update is a real diff."""
    pinned = report["pinned"]
    live = report["live"] or {"layer": None, "schemas": {}}
    lines = [
        "Telegram's production layer or schema endpoints no longer match the "
        "accepted pinned state in this repository. Opened and updated automatically by "
        "`.github/workflows/schema-drift.yml`; it never edits `schema/` or "
        "`Layer.java`.",
        "",
        "| | Pinned | Official current |",
        "|---|---|---|",
        "| Layer | %s | %s |" % (pinned["layer"], live["layer"]
                                 if live["layer"] is not None else "unavailable"),
    ]
    for name in sorted(pinned["schemas"]):
        entry = live["schemas"].get(name)
        lines.append("| `%s` | `%s` | %s |" % (
            name, pinned["schemas"][name]["sha256"],
            "`%s`" % entry["sha256"] if entry else "unavailable"))
    lines.append("")

    for section, items in (("What changed", report["findings"]),
                           ("Next steps", report["next_steps"])):
        if items:
            lines.append("### %s" % section)
            lines.append("")
            for item in items:
                lines.append("- %s" % item)
            lines.append("")

    lines.append("Sources: %s, %s, %s" % (CONFIG_URL, API_URL, MTPROTO_URL))
    return "\n".join(lines).rstrip() + "\n"


def format_console(report):
    lines = ["check-schema-drift: %s" % report["status"]]
    pinned = report["pinned"]
    if pinned:
        lines.append("  pinned layer %d" % pinned["layer"])
        for name in sorted(pinned["schemas"]):
            lines.append("  pinned %-13s %s" % (name, pinned["schemas"][name]["sha256"]))
    live = report["live"]
    if live:
        if live["layer"] is not None:
            lines.append("  live   layer %d" % live["layer"])
        for name in sorted(live["schemas"]):
            lines.append("  live   %-13s %s" % (name, live["schemas"][name]["sha256"]))
    for item in report["findings"] + report["problems"]:
        lines.append("  - %s" % item)
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Compare the pinned Telegram layer and schema with the official current ones.")
    parser.add_argument("--online", action="store_true",
                        help="consult core.telegram.org; off by default so builds stay offline")
    parser.add_argument("--json", metavar="PATH", help="write the machine-readable report here")
    parser.add_argument("--summary", metavar="PATH",
                        help="append a Markdown summary here (e.g. $GITHUB_STEP_SUMMARY)")
    parser.add_argument("--issue-body", metavar="PATH",
                        help="write the drift issue body here when there is drift")
    parser.add_argument("--timeout", type=float, default=30.0, metavar="SECONDS")
    args = parser.parse_args(argv)

    fetch = None
    if args.online:
        fetch = lambda url: urllib_fetch(url, timeout=args.timeout)

    report = check(REPO, fetch=fetch, online=args.online)

    print(format_console(report))

    if args.json:
        Path(args.json).write_text(json.dumps(report, indent=2, sort_keys=True) + "\n",
                                   encoding="utf-8")
    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as handle:
            handle.write(format_summary(report))
    if args.issue_body and report["status"] == STATUS_DRIFT:
        Path(args.issue_body).write_text(format_issue_body(report), encoding="utf-8")

    return EXIT_CODES[report["status"]]


if __name__ == "__main__":
    sys.exit(main())
