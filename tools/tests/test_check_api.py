import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "check-api.py"
ALLOW = ROOT / "config" / "cldc11-midp20-api.txt"
BUILD = ROOT / "tools" / "build.ps1"
PROGUARD = ROOT / "config" / "proguard-common.pro"


def load_module():
    spec = importlib.util.spec_from_file_location("check_api", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CheckApiConfigTest(unittest.TestCase):
    def test_member_separator_is_not_parsed_as_a_comment(self):
        module = load_module()
        _, denied = module.load_allow_list(ALLOW)
        self.assertIn("java/lang/Character#isWhitespace", denied)
        self.assertIn("java/lang/System#nanoTime", denied)
        self.assertGreater(len(denied), 50)

    def test_release_optimizer_cannot_synthesize_cldc_wrapper_factories(self):
        config = PROGUARD.read_text(encoding="utf-8")
        self.assertIn("-optimizations !code/simplification/object", config)

    def test_shipped_proguard_tree_is_api_checked(self):
        build = BUILD.read_text(encoding="utf-8")
        proguard_done = build.index("$kept = @(")
        post_check = build.index(
            '& $py (Join-Path $PSScriptRoot "check-api.py") $preverDir'
        )
        packaging = build.index('Write-Step "packaging"')
        self.assertLess(proguard_done, post_check)
        self.assertLess(post_check, packaging)


if __name__ == "__main__":
    unittest.main()
