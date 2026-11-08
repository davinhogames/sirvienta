from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class GitHubPagesBuildTests(unittest.TestCase):
    def test_release_asset_name_matches_the_manifest_download_url(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        workflow = (repository_root / ".github" / "workflows" / "publish.yml").read_text(
            encoding="utf-8"
        )
        workflow = " ".join(workflow.replace("\\\n", " ").split())

        self.assertIn(
            'cp android/app/build/outputs/apk/release/app-release.apk "$RUNNER_TEMP/sirvienta-android-beta.apk"',
            workflow,
        )
        self.assertIn(
            'gh release create sirvienta-latest "$RUNNER_TEMP/sirvienta-android-beta.apk" "$RUNNER_TEMP/latest.json"',
            workflow,
        )
        self.assertIn("/download/sirvienta-android-beta.apk", workflow)

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
            self.assertIn('src="download.js?v=4"', html)
            self.assertIn('aria-disabled="true"', html)
            self.assertIn('https://github.com/davinhogames/sirvienta', html)
            self.assertNotRegex(html, r'<a id="androidDownload"[^>]+href=')
            self.assertNotIn('href="/app"', html)
            self.assertNotIn('href="/static/', html)
            self.assertNotIn("apple-mobile-web-app-", html)
            self.assertIn("A tu ritmo", html)
            self.assertTrue((output_path / "download.js").is_file())
            self.assertTrue((output_path / "download.css").is_file())
            self.assertEqual(
                json.loads((output_path / "latest.json").read_text(encoding="utf-8")),
                manifest,
            )

    def test_builds_site_when_android_apk_is_not_published_yet(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            manifest_path = temporary_path / "latest.json"
            output_path = temporary_path / "site"
            manifest = {
                "available": False,
                "version_code": None,
                "version_name": None,
                "architecture": "arm64-v8a",
                "kind": "release",
                "size_bytes": None,
                "download_url": None,
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

            self.assertTrue((output_path / "index.html").is_file())
            self.assertEqual(
                json.loads((output_path / "latest.json").read_text(encoding="utf-8")),
                manifest,
            )

    def test_rejects_available_release_without_download_url(self) -> None:
        repository_root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_path = Path(temporary_directory)
            manifest_path = temporary_path / "latest.json"
            manifest_path.write_text(json.dumps({"available": True}), encoding="utf-8")

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
            self.assertIn("must include its download URL", result.stderr)


if __name__ == "__main__":
    unittest.main()
