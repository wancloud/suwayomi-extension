"""Build a Suwayomi-consumable extension repo from the locally built APKs.

Scans src/<lang>/<name>/build/ for the source-info JSON emitted by each
assembleRelease, copies the APKs, extracts the launcher icons, and writes
repo/index.min.json (the format Suwayomi and Mihon-style clients consume).

Usage: python scripts/create-repo.py
Requires ANDROID_HOME (or sdk.dir in local.properties) for aapt.
"""

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
REPO_DIR = ROOT / "repo"
APK_DIR = REPO_DIR / "apk"
ICON_DIR = REPO_DIR / "icon"

APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)


def find_sdk() -> Path:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        return Path(sdk)
    local_props = ROOT / "local.properties"
    if local_props.exists():
        for line in local_props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip())
    sys.exit("Android SDK not found: set ANDROID_HOME or sdk.dir in local.properties")


def find_aapt() -> Path:
    build_tools = sorted((find_sdk() / "build-tools").iterdir())
    if not build_tools:
        sys.exit("No build-tools installed in the Android SDK")
    exe = "aapt.exe" if os.name == "nt" else "aapt"
    return build_tools[-1] / exe


def main() -> None:
    aapt = find_aapt()
    APK_DIR.mkdir(parents=True, exist_ok=True)
    ICON_DIR.mkdir(parents=True, exist_ok=True)

    extensions = []
    for info_file in sorted(ROOT.glob("src/*/*/build/keiyoushi-source-info.json")):
        info = json.loads(info_file.read_text(encoding="utf-8"))
        package_name = info["packageName"]

        apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
        if apk is None:
            sys.exit(f"{package_name}: no release APK found — run assembleRelease first")

        apk_name = apk.name.replace("-release.apk", ".apk")
        shutil.copyfile(apk, APK_DIR / apk_name)

        badging = subprocess.check_output(
            [str(aapt), "dump", "--include-meta-data", "badging", str(apk)]
        ).decode(errors="replace")
        icon_match = APPLICATION_ICON_320_REGEX.search(badging)
        if icon_match:
            with ZipFile(apk) as z, z.open(icon_match.group(1)) as i:
                (ICON_DIR / f"{package_name}.png").write_bytes(i.read())

        lang = info_file.parent.parent.parent.name
        if len(info["sources"]) == 1:
            lang = info["sources"][0]["lang"]

        extensions.append(
            {
                "name": f"Tachiyomi: {info['name']}",
                "pkg": package_name,
                "apk": apk_name,
                "lang": lang,
                "code": info["versionCode"],
                "version": info["versionName"],
                # contentWarning: 1=SAFE 2=MIXED 3=NSFW; legacy index only has a boolean
                "nsfw": 1 if info["contentWarning"] > 2 else 0,
                "sources": [
                    {
                        "name": s["name"],
                        "lang": s["lang"],
                        "id": str(s["id"]),
                        "baseUrl": s["baseUrl"],
                    }
                    for s in info["sources"]
                ],
            }
        )

    if not extensions:
        sys.exit("No built extensions found — run assembleRelease first")

    extensions.sort(key=lambda e: e["pkg"])
    index_min = REPO_DIR / "index.min.json"
    index_min.write_text(
        json.dumps(extensions, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    (REPO_DIR / "index.json").write_text(
        json.dumps(extensions, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(f"Wrote {index_min} with {len(extensions)} extension(s):")
    for ext in extensions:
        print(f"  {ext['pkg']} v{ext['version']} ({ext['apk']})")


if __name__ == "__main__":
    main()
