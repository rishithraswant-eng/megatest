"""Exercise resume/range handling without requesting network or real model weights."""
import hashlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fetch_reference_models import download


class Response(io.BytesIO):
    def __init__(self, data, start, end, total):
        super().__init__(data[start:end + 1])
        self.status = 206
        self.headers = {"Content-Range": f"bytes {start}-{end}/{total}"}


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.data = bytes(range(256)) * 4
        self.model = {"repo": "test", "revision": "test", "file": "fixture.bin",
                      "size": len(self.data), "sha256": hashlib.sha256(self.data).hexdigest()}

    def response(self, request, **kwargs):
        start, end = map(int, request.get_header("Range").removeprefix("bytes=").split("-"))
        return Response(self.data, start, end, len(self.data))

    def test_parallel_resume_preserves_existing_prefix(self):
        (self.root / "fixture.bin.part").write_bytes(self.data[:73])
        with patch("fetch_reference_models.urllib.request.urlopen", side_effect=self.response):
            download(self.root, self.model)
        self.assertEqual(self.data, (self.root / "fixture.bin").read_bytes())
        self.assertEqual([], list(self.root.glob("*.part*")))

    def test_verified_cached_artifact_does_not_request_network(self):
        (self.root / "fixture.bin").write_bytes(self.data)
        with patch("fetch_reference_models.urllib.request.urlopen") as request:
            download(self.root, self.model)
            request.assert_not_called()

    def test_resuming_disjoint_fragments_can_change_connection_count(self):
        (self.root / "fixture.bin.part").write_bytes(self.data[:73])
        (self.root / "fixture.bin.part.range-73-500").write_bytes(self.data[73:200])
        (self.root / "fixture.bin.part.range-501-1023").write_bytes(self.data[501:800])
        with patch("fetch_reference_models.urllib.request.urlopen", side_effect=self.response):
            download(self.root, self.model, workers=8)
        self.assertEqual(self.data, (self.root / "fixture.bin").read_bytes())

    def test_bad_existing_artifact_is_not_overwritten(self):
        (self.root / "fixture.bin").write_bytes(b"bad")
        with self.assertRaisesRegex(ValueError, "checksum"):
            download(self.root, self.model)
        self.assertEqual(b"bad", (self.root / "fixture.bin").read_bytes())

    def test_incorrect_server_range_is_rejected(self):
        def incorrect(request, **kwargs):
            return Response(self.data, 0, 3, len(self.data))
        with patch("fetch_reference_models.urllib.request.urlopen", side_effect=incorrect):
            with self.assertRaisesRegex(ValueError, "range"):
                download(self.root, self.model)
        self.assertFalse((self.root / "fixture.bin").exists())


if __name__ == "__main__":
    unittest.main()
