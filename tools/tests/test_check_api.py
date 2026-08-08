import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "check-api.py"
ALLOW = ROOT / "config" / "cldc11-midp20-api.txt"


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


if __name__ == "__main__":
    unittest.main()
