"""Check package/version/sdk/production banner and emit public release provenance."""
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

output = Path("release-output")
package = "com.jonkryl.homesession"
code = os.environ["HOME_SESSION_VERSION_CODE"]
version = os.environ["HOME_SESSION_VERSION_NAME"]
banner = os.environ["HOME_SESSION_YANDEX_BANNER_ID"]
if not re.fullmatch(r"R-M-\d+-\d+", banner):
    raise SystemExit("Production banner must be a real R-M ID")
badging = (output / "package.txt").read_text()
for expected in (f"package: name='{package}' versionCode='{code}' versionName='{version}'", "sdkVersion:'24'", "targetSdkVersion:'36'"):
    if expected not in badging:
        raise SystemExit(f"APK metadata mismatch: {expected}")
manifest = ET.parse(output / "aab-manifest.xml").getroot()
android = "{http://schemas.android.com/apk/res/android}"
uses_sdk = manifest.find("uses-sdk")
if manifest.get("package") != package or manifest.get(android + "versionCode") != code or manifest.get(android + "versionName") != version:
    raise SystemExit("AAB package/version differs from expected release")
if uses_sdk is None or uses_sdk.get(android + "minSdkVersion") != "24" or uses_sdk.get(android + "targetSdkVersion") != "36":
    raise SystemExit("AAB min/target SDK differs from expected 24/36")
application = manifest.find("application")
if application is None or application.get(android + "debuggable", "false") != "false":
    raise SystemExit("A stable release must not be debuggable")
metadata = {item.get(android + "name"): item.get(android + "value") for item in application.findall("meta-data")}
if metadata.get("com.yandex.mobile.ads.AUTOMATIC_SDK_INITIALIZATION") != "false":
    raise SystemExit("SDK auto initialization must remain off so privacy is applied first")
with zipfile.ZipFile(sys.argv[1]) as archive:
    bytecode = b"".join(archive.read(name) for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name))
if banner.encode() not in bytecode or b"demo-banner-yandex" in bytecode:
    raise SystemExit("Release must contain the real banner and no debug demo banner")
signature = (output / "apk-signature.txt").read_text()
match = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", signature)
if not match:
    raise SystemExit("APK upload certificate fingerprint is missing")
digest = match[1].upper()
artifacts = [{"name": Path(path).name, "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest(), "bytes": Path(path).stat().st_size} for path in sys.argv[1:]]
provenance = {
    "repository": os.environ["GITHUB_REPOSITORY"],
    "source_sha": os.environ["GITHUB_SHA"],
    "release_run_url": f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
    "package": package, "version_code": int(code), "version_name": version,
    "min_sdk": 24, "target_sdk": 36, "compile_sdk": 36,
    "upload_certificate_sha256": ":".join(digest[index:index+2] for index in range(0, len(digest), 2)),
    "yandex_banner_id": banner, "ads_test_mode": False,
    "debuggable": False, "sdk_automatic_initialization": False,
    "apk_signature_verified": True, "aab_signature_verified": True,
    "apk_zipalign_16kb_verified": True, "native_64bit_16kb_verified": True,
    "artifacts": artifacts,
    "quality_gates": json.loads((output / "quality-gates.json").read_text()),
    "google_play_production_verified": False,
}
(output / "release-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
print(json.dumps(provenance, indent=2))
