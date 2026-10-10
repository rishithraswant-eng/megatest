"""Bundle contract tests; small header fixtures are not inference models."""
import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from make_model_bundle import package_bundle, validate_spec


class ModelBundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        (self.root / "whisper.bin").write_bytes(b"lmgg" + b"fixture")
        (self.root / "qwen.gguf").write_bytes(b"GGUF" + b"fixture")
        self.spec = {"contract_version": 1, "pipeline": "asr_llm", "models": {
            "asr": {"file": "whisper.bin", "version": "fixture-v1", "family": "Whisper",
                    "backend": "whisper.cpp-cpu", "multilingual": True},
            "llm": {"file": "qwen.gguf", "version": "fixture-v1", "family": "Qwen3",
                    "backend": "llama.cpp-cpu"}}}

    def test_pair_bundle_contains_only_active_roles_and_checksums(self):
        output = self.root / "pair.zip"
        package_bundle(self.spec, self.root, output)
        with zipfile.ZipFile(output) as bundle:
            manifest = json.loads(bundle.read("manifest.json"))
            self.assertEqual(set(manifest["models"]), {"asr", "llm"})
            self.assertEqual(set(bundle.namelist()), {"manifest.json", "whisper.bin", "qwen.gguf"})
            for name, digest in manifest["sha256"].items():
                self.assertEqual(hashlib.sha256(bundle.read(name)).hexdigest(), digest)
        self.assertNotIn("sha256", self.spec)

    def test_missing_full_models_cannot_silently_enable_partial_mode(self):
        del self.spec["pipeline"]
        with self.assertRaisesRegex(ValueError, "roles"):
            validate_spec(self.spec, self.root)

    def test_unknown_mode_rejected(self):
        self.spec["pipeline"] = "auto"
        with self.assertRaisesRegex(ValueError, "roles"):
            validate_spec(self.spec, self.root)

    def test_unexpected_role_rejected(self):
        self.spec["models"]["cfc"] = {}
        with self.assertRaisesRegex(ValueError, "roles"):
            validate_spec(self.spec, self.root)

    def test_wrong_header_rejected(self):
        (self.root / "qwen.gguf").write_bytes(b"not a model")
        with self.assertRaisesRegex(ValueError, "header"):
            validate_spec(self.spec, self.root)

    def test_escaping_path_rejected(self):
        self.spec["models"]["llm"]["file"] = "../outside.gguf"
        with self.assertRaisesRegex(ValueError, "escapes"):
            validate_spec(self.spec, self.root)

    def test_english_only_asr_rejected(self):
        self.spec["models"]["asr"]["multilingual"] = False
        with self.assertRaisesRegex(ValueError, "multilingual"):
            validate_spec(self.spec, self.root)

    def test_wrong_backend_rejected(self):
        self.spec["models"]["llm"]["backend"] = "qnn"
        with self.assertRaisesRegex(ValueError, "backend"):
            validate_spec(self.spec, self.root)


if __name__ == "__main__":
    unittest.main()
