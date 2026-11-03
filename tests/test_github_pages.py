from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class GitHubPagesBuildTests(unittest.TestCase):
    def test_builds_self_contained_download_site_from_release_manifest(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            manifest_path = temporary_path / "latest.json"
            output_path = temporary_path / "site"
            manifest = {
                "available": True,
                "version_code": 3,
                "version_name": "0.1.1-build.1",
                "size_bytes": 1000,
                "architecture": "arm64-v8a",
                "download_url": "https://github.com/example/sirvienta/releases/latest/download/sirvienta-android-beta.apk",
            }
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            subprocess.run(
                [
                    sys.executable,
                    str(repository_root / "scripts" / "build_github_pages.py"),
                    "--web-dir",
                    str(repository_root / "web"),
                    "--release-manifest",
                    str(manifest_path),
                    "--output-dir",
                    str(output_path),
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            html = (output_path / "index.html").read_text(encoding="utf-8")
            self.assertIn('href="#inicio"', html)
            self.assertIn('window.SIRVIENTA_RELEASE_MANIFEST_URL = "latest.json"', html)
            self.assertNotIn('href="/app"', html)
            self.assertTrue((output_path / "download.js").is_file())
            self.assertTrue((output_path / "download.css").is_file())
            self.assertEqual(json.loads((output_path / "latest.json").read_text()), manifest)

    def test_rejects_unavailable_release_manifest(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            manifest_path = temporary_path / "latest.json"
            manifest_path.write_text(json.dumps({"available": False}), encoding="utf-8")

            result = subprocess.run(
                [
                    sys.executable,
                    str(repository_root / "scripts" / "build_github_pages.py"),
                    "--web-dir",
                    str(repository_root / "web"),
                    "--release-manifest",
                    str(manifest_path),
                    "--output-dir",
                    str(temporary_path / "site"),
                ],
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("available Android APK", result.stderr)


if __name__ == "__main__":
    unittest.main()
