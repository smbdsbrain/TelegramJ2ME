import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "import-tl-schema.py"


def load_module():
    spec = importlib.util.spec_from_file_location("import_tl_schema", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SCHEMA = """
int ? = Int;
bytes = Bytes;

boolFalse#bc799737 = Bool;
vector#1cb5c415 {t:Type} # [ t ] = Vector t;

---types---
sample#10203040 flags:# flags2:# value:flags2.19?Vector<string> = Sample;

---functions---
invokeWithLayer#da9b0d0d {X:Type} layer:int query:!X = X;
"""


class ParserTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def test_flags_generics_namespaces_and_vector(self):
        schema = self.module.parse_tl(SCHEMA)
        self.assertEqual(3, len(schema["constructors"]))
        self.assertEqual(1, len(schema["methods"]))
        vector = schema["constructors"][1]
        self.assertEqual("vector", vector["predicate"])
        self.assertEqual([], vector["params"])
        sample = schema["constructors"][2]
        self.assertEqual("flags2.19?Vector<string>", sample["params"][2]["type"])
        method = schema["methods"][0]
        self.assertEqual("invokeWithLayer", method["method"])
        self.assertEqual(-627372787, int(method["id"]))
        self.assertEqual([{"name": "layer", "type": "int"},
                          {"name": "query", "type": "!X"}], method["params"])

    def test_malformed_declaration_is_rejected(self):
        with self.assertRaises(self.module.SchemaError):
            self.module.parse_tl(SCHEMA + "broken#1234 not-a-param = Broken;\n")

    def test_unbalanced_generic_is_rejected(self):
        with self.assertRaises(self.module.SchemaError):
            self.module.parse_tl(SCHEMA + "broken#1234 value:Vector<int = Broken;\n")

    def test_duplicate_name_is_rejected(self):
        duplicate = SCHEMA.replace("---functions---",
                                   "sample#10203041 = Sample;\n---functions---")
        with self.assertRaises(self.module.SchemaError):
            self.module.parse_tl(duplicate)


class CommandTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()

    def test_write_and_check_are_byte_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "api.tl"
            output = pathlib.Path(directory) / "api.json"
            source.write_text(SCHEMA, encoding="utf-8")
            self.assertEqual(0, self.module.main([str(source), str(output)]))
            first = output.read_bytes()
            self.assertEqual(0, self.module.main(
                ["--check", str(source), str(output)]))
            self.assertEqual(first, output.read_bytes())
            self.assertEqual(first, json.dumps(
                self.module.parse_tl(SCHEMA), ensure_ascii=False,
                separators=(",", ":")).encode("utf-8"))

    def test_check_detects_a_stale_json_file(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "api.tl"
            output = pathlib.Path(directory) / "api.json"
            source.write_text(SCHEMA, encoding="utf-8")
            output.write_text("{}", encoding="utf-8")
            self.assertEqual(1, self.module.main(
                ["--check", str(source), str(output)]))


class PinnedSchemaTest(unittest.TestCase):
    def test_committed_layer_225_source_reproduces_json(self):
        module = load_module()
        source = ROOT / "schema" / "api.tl"
        output = ROOT / "schema" / "api.json"
        self.assertEqual(
            "aa21644954119b6b8be10c839c24cbbeff389d7158f65a5a0419887014f89a93",
            hashlib.sha256(source.read_bytes()).hexdigest())
        rendered = module.render_tl(source.read_text(encoding="utf-8"))
        self.assertEqual(output.read_bytes(), rendered)
        schema = json.loads(rendered.decode("utf-8"))
        self.assertEqual(1573, len(schema["constructors"]))
        self.assertEqual(782, len(schema["methods"]))


if __name__ == "__main__":
    unittest.main()
