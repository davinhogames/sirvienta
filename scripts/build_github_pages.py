from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path


def build_site(web_dir: Path, release_manifest: Path, output_dir: Path) -> None:
    manifest = json.loads(release_manifest.read_text(encoding="utf-8"))
    if manifest.get("available") and not manifest.get("download_url"):
        raise ValueError("An available Android release must include its download URL.")

    output_dir.mkdir(parents=True, exist_ok=True)
    for asset_name in ("download.css", "download.js", "icon.svg", "apple-touch-icon.png"):
        shutil.copy2(web_dir / asset_name, output_dir / asset_name)

    html = (web_dir / "download.html").read_text(encoding="utf-8")
    html = re.sub(
        r'^[ \t]*<meta name="apple-mobile-web-app-[^"]+"\s+content="[^"]+"\s*/>[ \t]*\r?\n',
        "",
        html,
        flags=re.MULTILINE,
    )
    html = re.sub(r'^[ \t]*<link rel="manifest"[^>]+/>[ \t]*\r?\n', "", html, flags=re.MULTILINE)
    html = html.replace('href="/descargar"', 'href="#inicio"')
    html = html.replace('href="/app"', 'href="#iphone"')
    html = html.replace(
        'class="button button-light platform-button"',
        'class="button button-light platform-button is-unavailable"',
    )
    html = html.replace('href="/downloads/android"', 'aria-disabled="true"')
    html = html.replace("/static/", "")
    html = html.replace(
        '    <script src="download.js?v=4" defer></script>',
        '    <script>window.SIRVIENTA_PUBLIC_SITE = true; '
        'window.SIRVIENTA_RELEASE_MANIFEST_URL = "latest.json";</script>\n'
        '    <script src="download.js?v=4" defer></script>',
    )
    (output_dir / "index.html").write_text(html, encoding="utf-8")
    shutil.copy2(release_manifest, output_dir / "latest.json")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build the static Sirvienta download site.")
    parser.add_argument("--web-dir", type=Path, required=True)
    parser.add_argument("--release-manifest", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    build_site(args.web_dir, args.release_manifest, args.output_dir)


if __name__ == "__main__":
    main()
