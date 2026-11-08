from __future__ import annotations

import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from sirvienta.app import create_app
from sirvienta.config import AppConfig


class DownloadPageTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        config = AppConfig(database_path=str(Path(self.temporary_directory.name) / "sirvienta.db"))
        with patch("sirvienta.app.get_config", return_value=config):
            app = create_app()
        self.endpoints = {
            route.name: route.endpoint
            for route in app.routes
            if hasattr(route, "endpoint") and getattr(route, "name", None)
        }

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_homepage_serves_download_landing_page(self) -> None:
        html = asyncio.run(self.endpoints["index"]())

        self.assertIn("Descarga Sirvienta", html)
        self.assertIn("href=\"/downloads/android\"", html)
        self.assertIn("href=\"/app\"", html)

    def test_assistant_dashboard_starts_voice_and_keeps_mic_controls(self) -> None:
        html = asyncio.run(self.endpoints["web_app"]())
        app_js = (
            Path(__file__).resolve().parents[1] / "web" / "app.js"
        ).read_text(encoding="utf-8")
        styles = (
            Path(__file__).resolve().parents[1] / "web" / "styles.css"
        ).read_text(encoding="utf-8")

        self.assertIn("Escucha manos libres", html)
        self.assertIn("Reintentar", html)
        self.assertIn("Apagar", html)
        self.assertIn("startVoiceListener();", app_js)
        self.assertIn("vuelve a decirlo para quedar en espera", app_js)
        self.assertIn("classList.toggle('voice-active', active)", app_js)
        self.assertIn(".voice-active .orb-core", styles)

    def test_download_metadata_matches_built_apk(self) -> None:
        download_info = asyncio.run(self.endpoints["downloads"]())
        release = asyncio.run(self.endpoints["android_release"]())
        apk_path = (
            Path(__file__).resolve().parents[1]
            / "android"
            / "app"
            / "build"
            / "outputs"
            / "apk"
            / "debug"
            / "app-debug.apk"
        )
        metadata_path = apk_path.with_name("release.json")
        build_metadata = (
            json.loads(metadata_path.read_text(encoding="utf-8"))
            if metadata_path.is_file()
            else {}
        )
        is_current = (
            apk_path.is_file()
            and build_metadata.get("apk_last_modified") == apk_path.stat().st_mtime_ns // 1_000_000
        )

        self.assertEqual(release["available"], is_current)
        self.assertEqual(download_info["android"]["available"], is_current)
        self.assertEqual(release["download_url"], "/downloads/android" if is_current else None)
        self.assertEqual(download_info["android"]["version_code"], release["version_code"])
        self.assertEqual(download_info["android"]["version_name"], release["version_name"])
        if is_current:
            self.assertEqual(download_info["android"]["size_bytes"], apk_path.stat().st_size)

    def test_android_download_response_points_to_built_apk(self) -> None:
        if not self._has_current_apk():
            self.skipTest("Android debug APK has not been built in this workspace.")
        response = asyncio.run(self.endpoints["download_android"]())

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.media_type, "application/vnd.android.package-archive")
        self.assertEqual(
            Path(response.path).name,
            "app-debug.apk",
        )

    def test_download_route_returns_apk_metadata(self) -> None:
        if not self._has_current_apk():
            self.skipTest("Android debug APK has not been built in this workspace.")
        response = asyncio.run(self.endpoints["head_download_android"]())

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["content-type"], "application/vnd.android.package-archive")
        self.assertEqual(
            response.headers["content-disposition"],
            'attachment; filename="sirvienta-android-beta.apk"',
        )
        self.assertEqual(response.body, b"")

    @staticmethod
    def _has_current_apk() -> bool:
        apk_path = (
            Path(__file__).resolve().parents[1]
            / "android"
            / "app"
            / "build"
            / "outputs"
            / "apk"
            / "debug"
            / "app-debug.apk"
        )
        metadata_path = apk_path.with_name("release.json")
        if not apk_path.is_file() or not metadata_path.is_file():
            return False
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        return metadata.get("apk_last_modified") == apk_path.stat().st_mtime_ns // 1_000_000
