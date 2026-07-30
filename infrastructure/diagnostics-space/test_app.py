import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


def diagnostic_zip(secret: bytes | None = None) -> bytes:
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        archive.writestr("manifest.json", json.dumps({"appVersion": "0.1.50"}))
        archive.writestr("events.jsonl", secret or b'{"category":"app","name":"process.start"}\n')
    return stream.getvalue()


class ArchiveValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.temp.cleanup()

    def write(self, content: bytes) -> Path:
        path = Path(self.temp.name) / "report.zip"
        path.write_bytes(content)
        return path

    def test_accepts_expected_archive(self):
        import app

        result = app.validate_archive(self.write(diagnostic_zip()))
        self.assertEqual(result["manifestVersion"], "0.1.50")
        self.assertEqual(result["entryCount"], 2)

    def test_rejects_obvious_unredacted_cookie(self):
        import app
        from fastapi import HTTPException

        path = self.write(diagnostic_zip(b'{"cookie":"session-secret-value"}\n'))
        with self.assertRaises(HTTPException):
            app.validate_archive(path)

    def test_rejects_path_traversal(self):
        import app
        from fastapi import HTTPException

        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("manifest.json", "{}")
            archive.writestr("events.jsonl", "{}\n")
            archive.writestr("../escape.txt", "bad")
        with self.assertRaises(HTTPException):
            app.validate_archive(self.write(stream.getvalue()))

    def test_upload_returns_receipt_and_stores_privately(self):
        import app
        from fastapi.testclient import TestClient

        with mock.patch.object(app, "DATA_ROOT", Path(self.temp.name)):
            app.recent_by_client.clear()
            app.daily_usage.clear()
            response = TestClient(app.app).post(
                "/v1/reports",
                data={
                    "report_id": "ANL-20260730-ABC123DE45",
                    "trigger": "manual",
                    "app_version": "0.1.50",
                    "version_code": "51",
                    "build_sha": "abcdef123456",
                    "platform": "android",
                },
                files={"report": ("report.zip", diagnostic_zip(), "application/zip")},
            )

            self.assertEqual(response.status_code, 200, response.text)
            self.assertEqual(response.json()["status"], "accepted")
            self.assertEqual(response.json()["reportId"], "ANL-20260730-ABC123DE45")
            self.assertIn("validate;dur=", response.headers["server-timing"])
            self.assertTrue((Path(self.temp.name) / "ANL-20260730-ABC123DE45.zip").is_file())
            self.assertTrue((Path(self.temp.name) / "ANL-20260730-ABC123DE45.json").is_file())


if __name__ == "__main__":
    unittest.main()
