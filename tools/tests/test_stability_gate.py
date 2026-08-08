import pathlib
import shutil
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
GATE = ROOT / "tools" / "stability-gate.ps1"
MATRIX = ROOT / "docs" / "testing" / "1.0-failure-matrix.md"


class StabilityGateSelfTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pwsh = shutil.which("pwsh")
        if cls.pwsh is None:
            raise unittest.SkipTest("pwsh is required by the repository toolchain")

    def run_gate(self, mode, matrix=None):
        command = [self.pwsh, "-NoProfile", "-File", str(GATE),
                   "-SelfTest", mode]
        if matrix is not None:
            command.extend(["-MatrixPath", str(matrix)])
        return subprocess.run(command, cwd=ROOT, text=True,
                              stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, timeout=30)

    def test_matrix_contract_passes(self):
        result = self.run_gate("Matrix")
        self.assertEqual(0, result.returncode, result.stdout)

    def test_pass_probe_returns_zero(self):
        result = self.run_gate("Pass")
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("SELF-PASS", result.stdout)

    def test_failure_probe_returns_nonzero(self):
        result = self.run_gate("Fail")
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("SELF-FAIL", result.stdout)

    def test_missing_required_matrix_id_fails(self):
        text = MATRIX.read_text(encoding="utf-8")
        text = "\n".join(line for line in text.splitlines()
                         if not line.startswith("| AUTH-01 |"))
        with tempfile.TemporaryDirectory() as directory:
            broken = pathlib.Path(directory) / "matrix.md"
            broken.write_text(text, encoding="utf-8")
            result = self.run_gate("Matrix", broken)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("AUTH-01", result.stdout)


if __name__ == "__main__":
    unittest.main()
