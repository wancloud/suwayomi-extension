"""Build a Suwayomi-consumable extension repo from the locally built APKs.

Scans src/<lang>/<name>/build/ for the source-info JSON emitted by each
assembleRelease, then writes a repo/ directory matching the layout of
keiyoushi's published repo:

  repo.json       repo descriptor; its index_v2 points modern Suwayomi at index.pb
  index.pb        gzipped protobuf index carrying jarUrl (lets Suwayomi skip dex2jar)
  index.json      same index as readable JSON
  index.min.json  legacy index for older Suwayomi/Tachiyomi clients
  apk/  jar/  icon/

Usage: python scripts/create-repo.py
Requires ANDROID_HOME (or sdk.dir in local.properties) for aapt/apksigner,
and the protobuf pip package for index.pb.
"""

import gzip
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile

from google.protobuf import json_format

sys.path.insert(0, str(Path(__file__).resolve().parent))
import index_pb2

ROOT = Path(__file__).resolve().parent.parent
REPO_DIR = ROOT / "repo"
APK_DIR = REPO_DIR / "apk"
JAR_DIR = REPO_DIR / "jar"
ICON_DIR = REPO_DIR / "icon"

REPO_NAME = "WanCloud Extensions"
BADGE_LABEL = "WAN"
WEBSITE = "https://github.com/wancloud/suwayomi-extension"
BASE_URL = "https://raw.githubusercontent.com/wancloud/suwayomi-extension/repo"

APPLICATION_ICON_320_REGEX = re.compile(r"^application-icon-320:'([^']+)'", re.MULTILINE)
CERT_DIGEST_REGEX = re.compile(r"SHA-256 digest: ([0-9a-f]{64})")


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


def build_tool(name: str) -> Path:
    build_tools = sorted((find_sdk() / "build-tools").iterdir())
    if not build_tools:
        sys.exit("No build-tools installed in the Android SDK")
    if os.name == "nt":
        for candidate in (f"{name}.exe", f"{name}.bat"):
            path = build_tools[-1] / candidate
            if path.exists():
                return path
    return build_tools[-1] / name


def cert_fingerprint(apk: Path) -> str:
    out = subprocess.check_output(
        [str(build_tool("apksigner")), "verify", "--print-certs", str(apk)]
    ).decode(errors="replace")
    match = CERT_DIGEST_REGEX.search(out)
    if not match:
        sys.exit(f"Could not read signing cert digest from {apk.name}")
    return match.group(1)


def main() -> None:
    aapt = build_tool("aapt")
    for directory in (APK_DIR, JAR_DIR, ICON_DIR):
        directory.mkdir(parents=True, exist_ok=True)

    signing_key = ""
    extensions: list[index_pb2.Extension] = []
    legacy_index = []

    for info_file in sorted(ROOT.glob("src/*/*/build/keiyoushi-source-info.json")):
        info = json.loads(info_file.read_text(encoding="utf-8"))
        package_name = info["packageName"]

        apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
        if apk is None:
            sys.exit(f"{package_name}: no release APK found — run assembleRelease first")
        jar = next((info_file.parent / "outputs/jar/release").glob("*.jar"), None)
        if jar is None:
            sys.exit(f"{package_name}: no release JAR found — run assembleRelease first")

        apk_name = apk.name.replace("-release.apk", ".apk")
        shutil.copyfile(apk, APK_DIR / apk_name)
        shutil.copyfile(jar, JAR_DIR / jar.name)

        if not signing_key:
            signing_key = cert_fingerprint(apk)

        badging = subprocess.check_output(
            [str(aapt), "dump", "--include-meta-data", "badging", str(apk)]
        ).decode(errors="replace")
        icon_match = APPLICATION_ICON_320_REGEX.search(badging)
        if icon_match:
            with ZipFile(apk) as z, z.open(icon_match.group(1)) as i:
                (ICON_DIR / f"{package_name}.png").write_bytes(i.read())

        extensions.append(
            index_pb2.Extension(
                name=info["name"],
                packageName=package_name,
                resources=index_pb2.Resources(
                    apkUrl=f"{BASE_URL}/apk/{apk_name}",
                    iconUrl=f"{BASE_URL}/icon/{package_name}.png",
                    jarUrl=f"{BASE_URL}/jar/{jar.name}",
                ),
                extensionLib=info["extensionLib"],
                versionCode=info["versionCode"],
                versionName=info["versionName"],
                contentWarning=info["contentWarning"],
                sources=[
                    index_pb2.Source(
                        id=int(source["id"]),
                        name=source["name"],
                        language=source["lang"],
                        homeUrl=source["baseUrl"],
                        mirrorUrls=source.get("mirrorUrls", []),
                    )
                    for source in info["sources"]
                ],
            )
        )

        lang = info_file.parent.parent.parent.name
        if len(info["sources"]) == 1:
            lang = info["sources"][0]["lang"]
        legacy_index.append(
            {
                "name": f"Tachiyomi: {info['name']}",
                "pkg": package_name,
                "apk": apk_name,
                "lang": lang,
                "code": info["versionCode"],
                "version": info["versionName"],
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

    extensions.sort(key=lambda ext: ext.packageName)
    legacy_index.sort(key=lambda ext: ext["pkg"])

    index = index_pb2.Index(
        name=REPO_NAME,
        badgeLabel=BADGE_LABEL,
        signingKey=signing_key,
        contact=index_pb2.Contact(website=WEBSITE),
        extensionList=index_pb2.ExtensionList(extensions=extensions),
    )

    (REPO_DIR / "index.pb").write_bytes(gzip.compress(index.SerializeToString()))
    (REPO_DIR / "index.json").write_text(
        json_format.MessageToJson(
            index,
            always_print_fields_with_no_presence=False,
            preserving_proto_field_name=True,
        ),
        encoding="utf-8",
    )
    (REPO_DIR / "index.min.json").write_text(
        json.dumps(legacy_index, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    (REPO_DIR / "repo.json").write_text(
        json.dumps(
            {
                "index_v2": f"{BASE_URL}/index.pb",
                "meta": {
                    "name": REPO_NAME,
                    "shortName": BADGE_LABEL,
                    "website": WEBSITE,
                    "signingKeyFingerprint": signing_key,
                },
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    print(f"Wrote {REPO_DIR} with {len(extensions)} extension(s):")
    for ext in extensions:
        print(f"  {ext.packageName} v{ext.versionName}")


if __name__ == "__main__":
    main()
