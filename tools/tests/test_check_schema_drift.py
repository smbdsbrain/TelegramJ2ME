#!/usr/bin/env python3
"""
Offline tests for tools/check-schema-drift.py.

Nothing here touches the network. Every "upstream" response is a fixture under
fixtures/, which is the only way to test the case that matters most - the
difference between *Telegram moved* and *we could not ask* - without waiting
for one of them to happen.

Run:
    python -m unittest discover -s tools/tests
    python tools/tests/test_check_schema_drift.py
"""

import hashlib
import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
FIXTURES = Path(__file__).resolve().parent / "fixtures"


def load_module():
    """The script has a dash in its name, so it cannot be imported by name."""
    path = REPO / "tools" / "check-schema-drift.py"
    spec = importlib.util.spec_from_file_location("check_schema_drift", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


drift = load_module()


def fixture(name):
    return (FIXTURES / name).read_bytes()


def sha256(data):
    return hashlib.sha256(data).hexdigest()


LAYER_JAVA = """package tg.mt;

public final class Layer
{
    public static final int LAYER = %d;

    private Layer() { }
}
"""

UPSTREAM_MD = """# Telegram TL schema

| | `api.json` | `mtproto.json` |
|---|---|---|
| Source | https://core.telegram.org/schema/json | https://core.telegram.org/schema/mtproto-json |
| Retrieved | 2026-07-27 | 2026-07-27 |
| SHA-256 | `%(api_sha)s` | `%(mtproto_sha)s` |
| Contents | %(api_constructors)d constructors, %(api_methods)d methods | %(mtproto_constructors)d constructors, %(mtproto_methods)d methods |

## Layer %(layer)d

Pinned by hand in `src/tg/mt/Layer.java`.
"""


class FakeRepo(object):
    """A minimal tree with the four files the monitor reads."""

    def __init__(self, layer=223, api=b"", mtproto=b""):
        self.root = Path(tempfile.mkdtemp(prefix="drift-test-"))
        (self.root / "src" / "tg" / "mt").mkdir(parents=True)
        (self.root / "schema").mkdir()
        (self.root / "src" / "tg" / "mt" / "Layer.java").write_text(
            LAYER_JAVA % layer, encoding="utf-8")
        (self.root / "schema" / "api.json").write_bytes(api)
        (self.root / "schema" / "mtproto.json").write_bytes(mtproto)
        self.write_upstream(layer=layer, api=api, mtproto=mtproto)

    def write_upstream(self, layer, api, mtproto):
        api_c, api_m = drift.count_schema(api, "api.json")
        mt_c, mt_m = drift.count_schema(mtproto, "mtproto.json")
        (self.root / "schema" / "UPSTREAM.md").write_text(UPSTREAM_MD % {
            "layer": layer,
            "api_sha": sha256(api),
            "mtproto_sha": sha256(mtproto),
            "api_constructors": api_c,
            "api_methods": api_m,
            "mtproto_constructors": mt_c,
            "mtproto_methods": mt_m,
        }, encoding="utf-8")

    def patch_upstream(self, old, new):
        path = self.root / "schema" / "UPSTREAM.md"
        text = path.read_text(encoding="utf-8")
        assert old in text, "cannot patch %r into the fixture" % old
        path.write_text(text.replace(old, new), encoding="utf-8")

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)


def responder(config=None, api=None, mtproto=None):
    """A fetch(url) -> bytes over fixtures. A value may be an exception."""
    table = {
        drift.CONFIG_URL: config,
        drift.API_URL: api,
        drift.MTPROTO_URL: mtproto,
    }

    def fetch(url):
        value = table[url]
        if isinstance(value, Exception):
            raise value
        if value is None:
            raise drift.Unavailable("%s not stubbed in this test" % url)
        return value

    return fetch


class PinnedFixture(unittest.TestCase):
    """Base for tests that need a consistent layer-223 repository."""

    LAYER = 223

    def setUp(self):
        self.api = fixture("api-pinned.json")
        self.mtproto = fixture("mtproto-pinned.json")
        self.repo = FakeRepo(layer=self.LAYER, api=self.api, mtproto=self.mtproto)
        self.addCleanup(self.repo.close)

    def check(self, **kwargs):
        return drift.check(self.repo.root, online=True, fetch=responder(**kwargs))


class CurrentIsNotDrift(PinnedFixture):

    def test_matching_layer_and_schemas_report_ok(self):
        report = self.check(config=fixture("config-layer-223.json"),
                            api=self.api, mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_OK, report["status"])
        self.assertEqual([], report["problems"])
        self.assertEqual(223, report["live"]["layer"])
        self.assertEqual(sha256(self.api), report["live"]["schemas"]["api.json"]["sha256"])
        self.assertEqual(0, drift.EXIT_CODES[report["status"]])

    def test_offline_run_reports_ok_without_consulting_anything(self):
        def explode(url):
            raise AssertionError("offline run fetched %s" % url)

        report = drift.check(self.repo.root, online=False, fetch=explode)
        self.assertEqual(drift.STATUS_OK, report["status"])
        self.assertIsNone(report["live"])
        self.assertEqual(223, report["pinned"]["layer"])


class LayerDrift(PinnedFixture):

    def test_layer_225_against_pinned_223_is_drift(self):
        report = self.check(config=fixture("config-layer-225.json"),
                            api=self.api, mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_DRIFT, report["status"])
        self.assertEqual(225, report["live"]["layer"])
        self.assertTrue(any("pinned 223, official current 225" in f
                            for f in report["findings"]), report["findings"])
        self.assertEqual(10, drift.EXIT_CODES[report["status"]])

    def test_the_report_says_only_the_layer_moved(self):
        report = self.check(config=fixture("config-layer-225.json"),
                            api=self.api, mtproto=self.mtproto)
        self.assertFalse(any("api.json changed" in f for f in report["findings"]),
                         report["findings"])
        self.assertTrue(any("layer number moved but both schema files are byte-identical" in s
                            for s in report["next_steps"]), report["next_steps"])


class SchemaDrift(PinnedFixture):

    def test_changed_schema_bytes_are_drift_even_at_the_same_layer(self):
        report = self.check(config=fixture("config-layer-223.json"),
                            api=fixture("api-drifted.json"), mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_DRIFT, report["status"])
        self.assertTrue(any(f.startswith("api.json changed") for f in report["findings"]),
                        report["findings"])
        self.assertFalse(any(f.startswith("mtproto.json changed") for f in report["findings"]),
                         report["findings"])

    def test_both_moving_is_reported_as_a_layer_upgrade(self):
        report = self.check(config=fixture("config-layer-225.json"),
                            api=fixture("api-drifted.json"), mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_DRIFT, report["status"])
        self.assertTrue(any("Both the layer and the schema moved" in s
                            for s in report["next_steps"]), report["next_steps"])

    def test_the_issue_body_carries_both_hashes_and_no_schema_diff(self):
        report = self.check(config=fixture("config-layer-225.json"),
                            api=fixture("api-drifted.json"), mtproto=self.mtproto)
        body = drift.format_issue_body(report)
        self.assertIn(sha256(self.api), body)
        self.assertIn(sha256(fixture("api-drifted.json")), body)
        self.assertIn("225", body)
        self.assertNotIn("somethingNew", body)


class MalformedUpstream(PinnedFixture):
    """An unreadable answer must never become a layer number."""

    def test_html_error_page_is_malformed_not_a_layer(self):
        report = self.check(config=fixture("config-not-json.html"),
                            api=self.api, mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_MALFORMED, report["status"])
        self.assertIsNone(report["live"]["layer"])
        self.assertEqual([], [f for f in report["findings"] if "Layer moved" in f])

    def test_missing_layer_field_is_malformed(self):
        report = self.check(config=fixture("config-no-layer.json"),
                            api=self.api, mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_MALFORMED, report["status"])
        self.assertIsNone(report["live"]["layer"])

    def test_layer_as_a_string_is_malformed(self):
        report = self.check(config=fixture("config-layer-not-int.json"),
                            api=self.api, mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_MALFORMED, report["status"])
        self.assertIsNone(report["live"]["layer"])

    def test_a_schema_endpoint_serving_html_is_malformed(self):
        report = self.check(config=fixture("config-layer-223.json"),
                            api=fixture("config-not-json.html"), mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_MALFORMED, report["status"])
        self.assertNotIn("api.json", report["live"]["schemas"])

    def test_malformed_outranks_drift_in_the_same_run(self):
        report = self.check(config=fixture("config-not-json.html"),
                            api=fixture("api-drifted.json"), mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_MALFORMED, report["status"])
        self.assertEqual(30, drift.EXIT_CODES[report["status"]])

    def test_parse_config_layer_rejects_a_float(self):
        self.assertRaises(drift.Malformed, drift.parse_config_layer, b'{"layer": 225.0}')

    def test_parse_config_layer_rejects_true(self):
        self.assertRaises(drift.Malformed, drift.parse_config_layer, b'{"layer": true}')


class NetworkFailure(PinnedFixture):
    """Not being able to ask is not an answer."""

    def test_unreachable_endpoint_is_unavailable_not_drift(self):
        report = self.check(config=drift.Unavailable("dns"),
                            api=drift.Unavailable("dns"),
                            mtproto=drift.Unavailable("dns"))
        self.assertEqual(drift.STATUS_UNAVAILABLE, report["status"])
        self.assertEqual([], report["findings"])
        self.assertEqual(20, drift.EXIT_CODES[report["status"]])

    def test_a_partial_outage_does_not_hide_the_drift_it_did_see(self):
        report = self.check(config=fixture("config-layer-225.json"),
                            api=drift.Unavailable("timed out"),
                            mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_UNAVAILABLE, report["status"])
        self.assertTrue(any("Layer moved" in f for f in report["findings"]),
                        report["findings"])
        self.assertTrue(any("unavailable" in p for p in report["problems"]),
                        report["problems"])

    def test_http_500_is_unavailable(self):
        report = self.check(config=drift.Unavailable("HTTP 500"),
                            api=self.api, mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_UNAVAILABLE, report["status"])
        self.assertTrue(any("HTTP 500" in p for p in report["problems"]))


class PinnedRecord(PinnedFixture):
    """The offline half: does the repository agree with itself?"""

    def test_a_schema_file_that_does_not_match_upstream_md_is_caught_offline(self):
        (self.repo.root / "schema" / "api.json").write_bytes(fixture("api-drifted.json"))
        report = drift.check(self.repo.root, online=False)
        self.assertEqual(drift.STATUS_PINNED_MISMATCH, report["status"])
        self.assertTrue(any("api.json hashes" in p for p in report["problems"]),
                        report["problems"])
        self.assertEqual(40, drift.EXIT_CODES[report["status"]])

    def test_layer_java_disagreeing_with_upstream_md_is_caught_offline(self):
        (self.repo.root / "src" / "tg" / "mt" / "Layer.java").write_text(
            LAYER_JAVA % 225, encoding="utf-8")
        report = drift.check(self.repo.root, online=False)
        self.assertEqual(drift.STATUS_PINNED_MISMATCH, report["status"])
        self.assertTrue(any("Layer.java pins layer 225" in p for p in report["problems"]),
                        report["problems"])

    def test_a_stale_constructor_count_is_caught_offline(self):
        self.repo.patch_upstream("2 constructors, 1 methods", "1546 constructors, 757 methods")
        report = drift.check(self.repo.root, online=False)
        self.assertEqual(drift.STATUS_PINNED_MISMATCH, report["status"])
        self.assertTrue(any("constructors but UPSTREAM.md records" in p
                            for p in report["problems"]), report["problems"])

    def test_pinned_mismatch_outranks_upstream_drift(self):
        self.repo.patch_upstream("## Layer 223", "## Layer 999")
        report = self.check(config=fixture("config-layer-225.json"),
                            api=self.api, mtproto=self.mtproto)
        self.assertEqual(drift.STATUS_PINNED_MISMATCH, report["status"])

    def test_missing_layer_heading_is_reported(self):
        self.repo.patch_upstream("## Layer 223", "## Layer")
        report = drift.check(self.repo.root, online=False)
        self.assertEqual(drift.STATUS_PINNED_MISMATCH, report["status"])
        self.assertTrue(any("no '## Layer <n>' heading" in p for p in report["problems"]),
                        report["problems"])


class RealRepository(unittest.TestCase):
    """The checked-in schema, checked without a network."""

    def test_this_repository_is_internally_consistent(self):
        report = drift.check(REPO, online=False)
        self.assertEqual(drift.STATUS_OK, report["status"],
                         "schema/UPSTREAM.md, schema/*.json and Layer.java disagree: %s"
                         % report["problems"])

    def test_upstream_md_parses_into_the_hashes_it_documents(self):
        record = drift.parse_upstream_record(
            (REPO / "schema" / "UPSTREAM.md").read_text(encoding="utf-8"))
        self.assertIsNotNone(record["layer"])
        for name in ("api.json", "mtproto.json"):
            self.assertIn("sha256", record["schemas"].get(name, {}))
            self.assertEqual(64, len(record["schemas"][name]["sha256"]))

    def test_the_summary_names_the_pinned_layer_and_both_hashes(self):
        report = drift.check(REPO, online=False)
        summary = drift.format_summary(report)
        self.assertIn(str(report["pinned"]["layer"]), summary)
        for name in ("api.json", "mtproto.json"):
            self.assertIn(report["pinned"]["schemas"][name]["sha256"], summary)


def strip_comments(text):
    """Code only. PowerShell, sh, Python and YAML all comment with '#'.

    Without this the guard below fires on a comment that merely *mentions*
    --online, which is how a useful check turns into one people delete.
    """
    out = []
    in_block = False
    for line in text.splitlines():
        stripped = line.strip()
        if in_block:
            if "#>" in stripped:
                in_block = False
            continue
        if stripped.startswith("<#"):
            in_block = "#>" not in stripped[2:]
            continue
        if stripped.startswith("#"):
            continue
        out.append(line)
    return "\n".join(out)


class OrdinaryBuildsStayOffline(unittest.TestCase):
    """The pinned workflow is the point; a monitor must not undo it."""

    ORDINARY = [
        "tools/bootstrap.ps1", "tools/bootstrap.sh",
        "tools/build.ps1", "tools/build.sh",
        "tools/test.ps1", "tools/test.sh",
        "tools/generate-tl.py", "tools/verify-tl-ids.py",
        ".github/workflows/ci.yml", ".github/workflows/release.yml",
    ]

    def code(self, name):
        path = REPO / name
        if not path.exists():
            return None
        return strip_comments(path.read_text(encoding="utf-8"))

    def test_no_ordinary_build_or_ci_path_runs_the_monitor_online(self):
        for name in self.ORDINARY:
            code = self.code(name)
            if code is None:
                continue
            self.assertNotIn("--online", code,
                             "%s would consult Telegram during an ordinary build" % name)

    def test_no_ordinary_path_downloads_a_schema(self):
        for name in self.ORDINARY:
            code = self.code(name)
            if code is None:
                continue
            for url in (drift.API_URL, drift.MTPROTO_URL, drift.CONFIG_URL):
                self.assertNotIn(url, code, "%s fetches %s" % (name, url))

    def test_the_guard_would_notice(self):
        """A stripper that eats everything would make both tests vacuous."""
        code = self.code("tools/test.ps1")
        self.assertIn("check-schema-drift.py", code)
        self.assertIn("--online", strip_comments("run --online now"))


WORKFLOW = REPO / ".github" / "workflows" / "schema-drift.yml"


class WorkflowText(unittest.TestCase):
    """The parts of the contract that can be checked without a YAML parser.

    PyYAML is not in the standard library and CI's setup-python does not ship
    it, so the structural tests below can skip. These cannot: the permission
    grant and the absence of a pull_request trigger are the two things that
    would matter most if they were ever quietly changed.
    """

    def setUp(self):
        self.assertTrue(WORKFLOW.exists(), "%s is missing" % WORKFLOW)
        self.text = WORKFLOW.read_text(encoding="utf-8")
        self.code = strip_comments(self.text)

    def test_the_default_permission_is_read_only(self):
        self.assertRegex(self.code, r"(?m)^permissions:\s*\n\s+contents:\s+read\s*$")

    def test_it_grants_no_write_beyond_issues(self):
        for scope in ("contents: write", "packages: write", "actions: write",
                      "pull-requests: write", "id-token: write", "write-all"):
            self.assertNotIn(scope, self.code, "unexpected grant: %s" % scope)
        self.assertIn("issues: write", self.code)

    def test_it_is_scheduled_and_manually_dispatchable(self):
        self.assertRegex(self.code, r"(?m)^\s+schedule:\s*$")
        self.assertRegex(self.code, r"(?m)^\s+-\s+cron:\s*'[\d*,/ -]+'\s*$")
        self.assertRegex(self.code, r"(?m)^\s+workflow_dispatch:\s*$")

    def test_it_is_not_wired_into_pull_request_ci(self):
        self.assertNotRegex(self.code, r"(?m)^\s+pull_request:")
        self.assertNotRegex(self.code, r"(?m)^\s+push:")

    def test_it_runs_the_monitor_online_and_writes_a_summary(self):
        self.assertIn("check-schema-drift.py", self.code)
        self.assertIn("--online", self.code)
        self.assertIn("GITHUB_STEP_SUMMARY", self.code)

    def test_it_never_writes_to_the_repository(self):
        for forbidden in ("git commit", "git push", "git add", "peter-evans/create-pull-request"):
            self.assertNotIn(forbidden, self.code,
                             "the monitor must not edit protocol files")


class WorkflowStructure(unittest.TestCase):
    """The same contract, parsed - which also proves the YAML is valid."""

    def setUp(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML not installed")
        self.assertTrue(WORKFLOW.exists(), "%s is missing" % WORKFLOW)
        self.doc = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        # PyYAML resolves the unquoted key `on:` to the boolean True (YAML 1.1),
        # which is why GitHub's own docs quote it. Accept either.
        self.triggers = self.doc.get("on", self.doc.get(True))

    def test_it_runs_on_a_schedule_and_on_demand(self):
        self.assertIn("schedule", self.triggers)
        self.assertIn("workflow_dispatch", self.triggers)
        self.assertTrue(self.triggers["schedule"], "no cron entry")
        for entry in self.triggers["schedule"]:
            self.assertRegex(entry["cron"], r"^[\d*,/ -]+$")

    def test_it_does_not_run_on_pull_requests_or_pushes(self):
        self.assertNotIn("pull_request", self.triggers)
        self.assertNotIn("push", self.triggers)

    def test_permissions_are_read_only_by_default(self):
        self.assertEqual({"contents": "read"}, self.doc.get("permissions"))

    def test_the_job_adds_only_issue_write(self):
        for job in self.doc["jobs"].values():
            granted = job.get("permissions", {})
            self.assertEqual("read", granted.get("contents"))
            extra = set(granted) - {"contents", "issues"}
            self.assertEqual(set(), extra, "unexpected permission: %s" % extra)
            self.assertNotEqual("write", granted.get("contents"))

    def test_it_runs_the_monitor_online(self):
        steps = [s for job in self.doc["jobs"].values() for s in job["steps"]]
        run = "\n".join(s.get("run", "") for s in steps)
        self.assertIn("check-schema-drift.py", run)
        self.assertIn("--online", run)

    def test_it_never_commits_or_pushes(self):
        steps = [s for job in self.doc["jobs"].values() for s in job["steps"]]
        run = "\n".join(s.get("run", "") for s in steps)
        for forbidden in ("git commit", "git push", "git add"):
            self.assertNotIn(forbidden, run,
                             "the monitor must not edit protocol files")

    def test_the_issue_steps_are_reachable_only_on_a_real_status(self):
        steps = [s for job in self.doc["jobs"].values() for s in job["steps"]]
        conditions = [s["if"] for s in steps if "issue" in (s.get("name") or "").lower()]
        self.assertEqual(2, len(conditions), "expected an open/update and a close step")
        self.assertTrue(any("'drift'" in c for c in conditions), conditions)
        self.assertTrue(any("'ok'" in c for c in conditions), conditions)
        # "unavailable" must never touch the issue: not being able to ask is
        # neither drift to report nor proof that drift is over.
        for condition in conditions:
            self.assertNotIn("unavailable", condition)


if __name__ == "__main__":
    unittest.main(verbosity=2 if "-v" in sys.argv else 1)
