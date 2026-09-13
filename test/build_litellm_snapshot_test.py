import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "build_litellm_snapshot.py"
SPEC = importlib.util.spec_from_file_location("build_litellm_snapshot", SCRIPT)
SNAPSHOT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SNAPSHOT)


class SnapshotProvenanceTest(unittest.TestCase):
    def test_local_override_has_local_origin_and_unknown_revision(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / SNAPSHOT.PRICING_FILENAME
            source.write_text(json.dumps({"custom/model": {"mode": "chat"}}))

            data, source_url, revision = SNAPSHOT.load_source(str(source))

            self.assertIn("custom/model", data)
            self.assertEqual(f"local:{source.name}", source_url)
            self.assertIsNone(revision)
            self.assertNotEqual(SNAPSHOT.LITELLM_REVISION, revision)

    def test_custom_url_records_url_and_only_an_embedded_revision(self):
        payload = json.dumps({}).encode()
        custom_url = "https://catalog.example/current.json"
        revision_url = (
            "https://catalog.example/"
            "0123456789abcdef0123456789abcdef01234567/catalog.json"
        )
        with mock.patch.object(SNAPSHOT, "fetch_bytes", return_value=payload):
            _, source_url, revision = SNAPSHOT.load_source(custom_url)
            _, pinned_url, pinned_revision = SNAPSHOT.load_source(revision_url)

        self.assertEqual(custom_url, source_url)
        self.assertIsNone(revision)
        self.assertEqual(revision_url, pinned_url)
        self.assertEqual("0123456789abcdef0123456789abcdef01234567",
                         pinned_revision)

    def test_custom_models_dev_checkout_does_not_claim_default_pin(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with mock.patch.object(SNAPSHOT, "load_toml_tree",
                                   return_value={"openai": {"models": {}}}):
                data, source_url, revision = SNAPSHOT.load_models_dev_source(
                    temp_dir
                )

            self.assertIn("openai", data)
            self.assertTrue(source_url.startswith("local:"))
            self.assertIsNone(revision)
            self.assertNotEqual(SNAPSHOT.MODELS_DEV_REVISION, revision)

    def test_unknown_revision_is_written_as_unknown(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "scripts").mkdir()
            fake_script = root / "scripts" / "build_litellm_snapshot.py"
            with mock.patch.object(SNAPSHOT, "__file__", str(fake_script)):
                SNAPSHOT.write_snapshot(
                    "custom.json", "local:custom.json", None, {"openai": {}}
                )
            document = json.loads((root / "resources" / "custom.json").read_text())

            self.assertEqual("local:custom.json", document["_meta"]["source_url"])
            self.assertIsNone(document["_meta"]["source_revision"])


if __name__ == "__main__":
    unittest.main()
