"""Verify public artifacts built and signed locally; never accept or use a private signing key."""
import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

REPOSITORY = "jonkryl/home-session"
PACKAGE = "com.jonkryl.homesession"
CERTIFICATE = "32:F3:2A:57:A1:08:96:B1:9C:9E:11:C7:10:9A:9C:AD:DC:69:34:06:21:F9:01:96:2B:D4:D1:78:CD:E4:97:14"
BANNER = "R-M-20166009-1"
BUNDLETOOL_VERSION = "1.18.3"
BUNDLETOOL_SHA256 = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
ANDROID = "{http://schemas.android.com/apk/res/android}"
REQUIRED_JOBS = {"Unit tests and Android lint", "Android API 24 journey", "Android API 36 journey", "All checks passed"}
EXPECTED_SUITES = {"com.jonkryl.homesession.ads.AdRetryPolicyTest": 2,
                   "com.jonkryl.homesession.core.HomeJsonTest": 11,
                   "com.jonkryl.homesession.core.HomePlannerTest": 13,
                   "com.jonkryl.homesession.core.HomeStoreTest": 25}
PAYLOAD_FILES = {"manifest.json", "source-manifest.json", "publiccertificate.pem", "quality-gates.json",
                 "release-provenance.json", "home-session-1.0.0-1.apk", "home-session-1.0.0-1.aab"}
SOURCE_INFRASTRUCTURE = {".github/workflows/bootstrap-source.yml", "source-import-receipt.json"}
VERIFICATION_ADDITIONS = {".github/workflows/verify-local-release.yml", "scripts/verify-public-release.py"} | {
    f"release-candidate/{name}" for name in PAYLOAD_FILES}
TEMPLATE_TASK_TITLES = {
    "ru": {"Освободить и протереть столешницу", "Вымыть раковину", "Проверить продукты в холодильнике",
           "Протереть раковину и кран", "Освежить зеркало", "Почистить душевую зону", "Вернуть вещи на места",
           "Убрать пыль с доступных поверхностей", "Пропылесосить свободный пол"},
    "en": {"Clear and wipe the worktop", "Wash the sink", "Check food in the fridge", "Wipe the basin and tap",
           "Freshen the mirror", "Clean the shower area", "Put loose items back", "Dust reachable surfaces",
           "Vacuum the clear floor"}}


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def safe_relative(value):
    require(isinstance(value, str) and value and "\\" not in value and "\x00" not in value, "Invalid relative payload path")
    path = PurePosixPath(value)
    require(not path.is_absolute() and all(part not in (".", "..", ".git") for part in path.parts), "Unsafe payload path")
    require(str(path) == value, "Noncanonical payload path")
    return path


def regular_file(root, relative):
    path = root.joinpath(*safe_relative(relative).parts)
    require(path.is_file() and not path.is_symlink(), f"Missing regular public file: {relative}")
    require(root.resolve() in path.resolve().parents, "Payload path escaped its root")
    return path


def run_tool(command, output=None):
    result = subprocess.run([str(item) for item in command], check=False, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    if output is not None:
        output.write_bytes(result.stdout)
    require(result.returncode == 0, f"Public verification command failed ({result.returncode}): {command[0]}\n" +
            result.stdout.decode("utf-8", errors="replace")[-4000:])
    return result.stdout.decode("utf-8", errors="replace")


def api(path):
    require(path.startswith(f"repos/{REPOSITORY}/"), "Unexpected GitHub API repository")
    return json.loads(run_tool(["gh", "api", path]))


def contract(payload):
    source_sha = os.environ.get("HOME_SESSION_SOURCE_SHA", "")
    ci_run_id = os.environ.get("HOME_SESSION_CI_RUN_ID", "")
    require(re.fullmatch(r"[0-9a-f]{40}", source_sha), "Source SHA must be exactly 40 lowercase hexadecimal characters")
    require(re.fullmatch(r"[1-9][0-9]{0,19}", ci_run_id), "CI run ID must be a positive decimal integer")
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "Only the authorized personal repository may verify this release")
    manifest = read_json(regular_file(payload, "manifest.json"))
    require({path.name for path in payload.iterdir()} == PAYLOAD_FILES and
            all(path.is_file() and not path.is_symlink() for path in payload.iterdir()),
            "Public candidate must contain exactly the seven approved public files")
    expected = {"repository": REPOSITORY, "source_sha": source_sha,
                "verified_ci_run_id": int(ci_run_id), "build_method": "local", "signing_method": "local",
                "package": PACKAGE, "version_name": "1.0.0", "version_code": 1,
                "min_sdk": 24, "target_sdk": 36, "compile_sdk": 36, "banner_id": BANNER,
                "upload_certificate_sha256": CERTIFICATE}
    for key, value in expected.items():
        require(type(manifest.get(key)) is type(value) and manifest.get(key) == value, f"Public manifest mismatch: {key}")
    require(manifest.get("google_play_production_verified") is False and manifest.get("paid_ads_delivery_verified") is False,
            "Public GitHub files must not claim Play production or paid delivery")
    require(set(manifest.get("artifacts", {})) == {"apk", "aab"}, "Exactly one APK and one AAB are required")
    for kind, item in manifest["artifacts"].items():
        require(item.get("path") == f"home-session-1.0.0-1.{kind}", "Unexpected public artifact name")
        require(re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", "")), "Invalid artifact SHA-256")
        require(isinstance(item.get("bytes"), int) and item["bytes"] > 0, "Invalid artifact byte count")
        path = regular_file(payload, item["path"])
        require(path.stat().st_size == item["bytes"] and sha256(path.read_bytes()) == item["sha256"], "Public artifact bytes changed")
    for path in payload.rglob("*"):
        require(not path.is_symlink(), "Public payload must not contain symlinks")
        require(path.suffix.lower() not in {".jks", ".keystore", ".p12", ".pfx", ".key"}, "Private signing material is forbidden")
        if path.is_file() and path.suffix.lower() in {".pem", ".json", ".txt", ".xml"}:
            require(b"PRIVATE KEY-----" not in path.read_bytes(), "Private key data is forbidden")
    regular_file(payload, "publiccertificate.pem")
    local_proof = read_json(regular_file(payload, "release-provenance.json"))
    require(local_proof.get("method") == "local" and local_proof.get("source_sha") == source_sha and
            local_proof.get("github_signed_release_workflow_executed") is False and local_proof.get("release_run_url") is None,
            "The public build receipt must truthfully describe local signing")
    environment = local_proof.get("build_environment")
    require(isinstance(environment, dict) and environment and manifest.get("build_environment") == environment,
            "Manifest and local receipt must declare the same local build environment")
    local_ci = read_json(regular_file(payload, "quality-gates.json"))
    require(local_ci.get("source_sha") == source_sha and local_ci.get("ci_run_id") == int(ci_run_id) and
            local_ci.get("conclusion") == "success", "The local receipt must refer to the same completed CI gates")
    return manifest


def verify_source(payload, source, current, manifest):
    require(run_tool(["git", "-C", source, "rev-parse", "HEAD"]).strip() == manifest["source_sha"], "Wrong immutable source checkout")
    inventory = read_json(regular_file(payload, "source-manifest.json"))
    require(inventory.get("git_source_sha", inventory.get("source_sha")) == manifest["source_sha"], "Source inventory commit mismatch")
    entries = inventory.get("files", [])
    require(len(entries) == 58 and inventory.get("source_file_count", len(entries)) == 58, "Expected the original 58-file source inventory")
    def git_tree(checkout):
        tree_bytes = subprocess.check_output(["git", "-C", str(checkout), "ls-tree", "-rz", "--full-tree", "HEAD"])
        result = {}
        for entry in tree_bytes.split(b"\0"):
            if not entry:
                continue
            header, name = entry.split(b"\t", 1)
            mode, kind, digest = header.decode().split()
            result[name.decode("utf-8")] = (mode, kind, digest)
        return result
    tree = git_tree(source)
    seen, proof = set(), []
    for item in entries:
        name = str(safe_relative(item["path"]))
        require(name not in seen, "Duplicate source inventory path")
        seen.add(name)
        data = regular_file(source, name).read_bytes()
        blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        require(item.get("sha256") == sha256(data) and item.get("bytes") == len(data), f"Source SHA-256/length mismatch: {name}")
        require(item.get("git_blob_sha1") == blob, f"Source inventory Git blob mismatch: {name}")
        require(name in tree and tree[name][1:] == ("blob", blob) and tree[name][0] in {"100644", "100755"}, f"Source tree blob mismatch: {name}")
        require(regular_file(current, name).read_bytes() == data, f"Current public checkout changed approved source: {name}")
        proof.append({"path": name, "bytes": len(data), "sha256": sha256(data), "git_blob_sha1": blob})
    require(set(tree) == seen | SOURCE_INFRASTRUCTURE, "Approved tree must contain exactly the 58 source files and two known importer/receipt files")
    current_tree = git_tree(current)
    require(set(current_tree) == set(tree) | VERIFICATION_ADDITIONS, "Current checkout contains unexpected changes or additions")
    require(all(current_tree[name] == entry for name, entry in tree.items()), "Current checkout changed an approved source or importer/receipt blob")
    require(all(current_tree[name][0] == "100644" and current_tree[name][1] == "blob" for name in VERIFICATION_ADDITIONS), "Public verification additions must be regular files")
    return {"source_sha": manifest["source_sha"], "verified_source_files": len(proof),
            "original_tree_file_count": len(tree), "current_58_source_files_unchanged": True,
            "original_tree_extras": [{"path": name, "mode": tree[name][0], "git_blob_sha1": tree[name][2]} for name in sorted(SOURCE_INFRASTRUCTURE)],
            "current_checkout_only_known_public_verification_additions": True,
            "files": proof}


def unzip_public_artifact(archive, destination):
    with zipfile.ZipFile(archive) as zipped:
        require(sum(info.file_size for info in zipped.infolist()) <= 100_000_000, "CI evidence archive is too large")
        for info in zipped.infolist():
            name = info.filename.rstrip("/")
            if not name:
                continue
            relative = safe_relative(name)
            require((info.external_attr >> 16) & 0o170000 != 0o120000, "CI evidence symlink is forbidden")
            require(info.file_size <= 25_000_000, "CI evidence entry is too large")
            path = destination.joinpath(*relative.parts)
            if info.is_dir():
                path.mkdir(parents=True, exist_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(zipped.read(info))


def verify_ci(manifest, output):
    run_id = manifest["verified_ci_run_id"]
    ci = api(f"repos/{REPOSITORY}/actions/runs/{run_id}")
    require(ci.get("head_sha") == manifest["source_sha"] and ci.get("status") == "completed" and ci.get("conclusion") == "success",
            "Prior CI must have completed successfully for the exact source commit")
    require(ci.get("path") == ".github/workflows/android-ci.yml", "Wrong prior quality workflow path")
    jobs = api(f"repos/{REPOSITORY}/actions/runs/{run_id}/jobs?per_page=100")["jobs"]
    for name in REQUIRED_JOBS:
        matches = [job for job in jobs if job["name"] == name]
        require(len(matches) == 1 and matches[0].get("status") == "completed" and matches[0].get("conclusion") == "success", f"Missing successful quality job: {name}")
    artifacts = api(f"repos/{REPOSITORY}/actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
    candidates = [artifact for artifact in artifacts if artifact["name"] == "unit-lint-reports" and not artifact["expired"]]
    require(len(candidates) == 1, "A unique nonexpired unit-lint-reports artifact is required")
    artifact = candidates[0]
    archive_path = output / "prior-ci-unit-lint-reports.zip"
    with archive_path.open("wb") as stream:
        subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{artifact['id']}/zip"], stdout=stream, check=True)
    archive_hash = sha256(archive_path.read_bytes())
    if artifact.get("digest"):
        require(artifact["digest"] == f"sha256:{archive_hash}", "CI evidence archive digest changed")
    with tempfile.TemporaryDirectory(prefix="home-session-ci-evidence-", dir=os.environ.get("RUNNER_TEMP")) as directory:
        evidence = Path(directory)
        unzip_public_artifact(archive_path, evidence)
        counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        suites = {}
        xml_files = sorted(evidence.rglob("TEST-*.xml"))
        for path in xml_files:
            suite = ET.parse(path).getroot()
            name = suite.get("name")
            require(name in EXPECTED_SUITES and name not in suites, "Unexpected or duplicate actual unit XML suite")
            cases = suite.findall("testcase")
            require(len(cases) == EXPECTED_SUITES[name] and int(suite.get("tests", "0")) == len(cases), "Actual unit test cases were omitted")
            require(not any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")), "Actual XML contains failed/error/skipped test cases")
            for key in counts:
                counts[key] += int(suite.get(key, "0"))
            suites[name] = len(cases)
        require(suites == EXPECTED_SUITES and counts == {"tests": 51, "failures": 0, "errors": 0, "skipped": 0}, "Exactly 51 actual passing cases and zero failures/errors/skips required")
        receipts = list(evidence.rglob("unit-summary.json"))
        require(len(receipts) == 1, "Prior CI unit summary receipt is missing or duplicated")
        summary = read_json(receipts[0])
        require(all(summary.get(key) == value for key, value in counts.items()) and set(summary.get("suites", [])) == set(suites), "Unit summary receipt disagrees with actual XML")
        lint_files = list(evidence.rglob("lint-results-debug.xml"))
        require(len(lint_files) == 1 and not any(issue.get("severity") in {"Error", "Fatal"} for issue in ET.parse(lint_files[0]).getroot().findall("issue")), "Prior Android lint is missing or contains errors")
    return {"source_sha": manifest["source_sha"], "ci_run_id": run_id, "ci_run_url": ci["html_url"],
            "workflow_path": ci["path"], "conclusion": "success",
            "jobs": [{"name": job["name"], "conclusion": job["conclusion"], "url": job["html_url"]} for job in jobs],
            "unit_xml": {**counts, "suites": suites, "summary_receipt_matched": True, "lint_errors": 0,
                         "artifact_id": artifact["id"], "artifact_zip_sha256": archive_hash}}


def certificate_digest(text, apk=False):
    pattern = r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})" if apk else r"SHA256:\s*([0-9a-fA-F:]+)"
    digests = re.findall(pattern, text)
    require(digests and (not apk or len(digests) == 1), "Public signer certificate was not uniquely identified")
    require(all(item.replace(":", "").lower() == CERTIFICATE.replace(":", "").lower() for item in digests), "Public signer certificate mismatch")


def verify_elf(archive_path):
    checked = []
    with zipfile.ZipFile(archive_path) as archive:
        for name in sorted(archive.namelist()):
            if not name.endswith(".so") or not re.match(r"(?:base/)?lib/(?:arm64-v8a|x86_64)/", name):
                continue
            data = archive.read(name)
            require(len(data) >= 64 and data[:4] == b"\x7fELF" and data[4] == 2 and data[5] in {1, 2}, f"Invalid 64-bit ELF: {name}")
            endian = "<" if data[5] == 1 else ">"
            ph_offset = struct.unpack_from(endian + "Q", data, 32)[0]
            ph_size, ph_count = struct.unpack_from(endian + "HH", data, 54)
            require(ph_size >= 56 and ph_offset + ph_size * ph_count <= len(data), f"Invalid ELF program headers: {name}")
            alignments = []
            for index in range(ph_count):
                values = struct.unpack_from(endian + "IIQQQQQQ", data, ph_offset + index * ph_size)
                if values[0] == 1:
                    require(values[7] >= 16_384 and values[2] % 16_384 == values[3] % 16_384, f"Native library lacks 16 KB PT_LOAD compatibility: {name}")
                    alignments.append(values[7])
            require(alignments, f"ELF has no load segments: {name}")
            checked.append({"path": name, "sha256": sha256(data), "minimum_load_alignment": min(alignments)})
    return checked


def apk_metadata(text, xmltree):
    for expected in ("package: name='com.jonkryl.homesession' versionCode='1' versionName='1.0.0'", "sdkVersion:'24'", "targetSdkVersion:'36'"):
        require(expected in text, "APK package/version/SDK mismatch")
    require("application-debuggable" not in text and "com.google.android.gms.permission.AD_ID" not in xmltree, "APK debugging or advertising-ID permission is present")
    lines = xmltree.splitlines()
    metadata = []
    for index, line in enumerate(lines):
        if "E: meta-data" not in line:
            continue
        indent = len(line) - len(line.lstrip())
        block = []
        for next_line in lines[index + 1:]:
            if "E: " in next_line and len(next_line) - len(next_line.lstrip()) <= indent:
                break
            block.append(next_line)
        metadata.append("\n".join(block))
    blocks = [block for block in metadata if "com.yandex.mobile.ads.AUTOMATIC_SDK_INITIALIZATION" in block]
    require(len(blocks) == 1 and re.search(r"android:value[^\n]*=\(type 0x12\)0x0\b", blocks[0]), "APK SDK automatic initialization must be explicitly false")


def aab_metadata(path):
    manifest = ET.parse(path).getroot()
    require(manifest.get("package") == PACKAGE and manifest.get(ANDROID + "versionCode") == "1" and manifest.get(ANDROID + "versionName") == "1.0.0", "AAB package/version mismatch")
    sdk = manifest.find("uses-sdk")
    require(sdk is not None and sdk.get(ANDROID + "minSdkVersion") == "24" and sdk.get(ANDROID + "targetSdkVersion") == "36", "AAB SDK mismatch")
    app = manifest.find("application")
    require(app is not None and app.get(ANDROID + "debuggable", "false") == "false", "AAB debugging must be false")
    values = {item.get(ANDROID + "name"): item.get(ANDROID + "value") for item in app.findall("meta-data")}
    require(values.get("com.yandex.mobile.ads.AUTOMATIC_SDK_INITIALIZATION") == "false", "AAB SDK automatic initialization must be false")
    require(not any(item.get(ANDROID + "name") == "com.google.android.gms.permission.AD_ID" for item in manifest.iter()), "AAB advertising-ID permission is present")


def verify_artifacts(payload, source, current, output, manifest):
    source_proof = verify_source(payload, source, current, manifest)
    quality = read_json(output / "quality-gates.json")
    require(quality["source_sha"] == manifest["source_sha"] and quality["ci_run_id"] == manifest["verified_ci_run_id"] and quality["conclusion"] == "success", "Prior CI gate proof is missing")
    android_home = Path(os.environ["ANDROID_HOME"])
    tools = android_home / "build-tools" / "36.0.0"
    apk = regular_file(payload, manifest["artifacts"]["apk"]["path"])
    aab = regular_file(payload, manifest["artifacts"]["aab"]["path"])
    signature = run_tool([tools / "apksigner", "verify", "--verbose", "--print-certs", apk], output / "apk-signature.txt")
    certificate_digest(signature, apk=True)
    run_tool([tools / "zipalign", "-c", "-P", "16", "-v", "4", apk], output / "apk-alignment.txt")
    jar = run_tool(["jarsigner", "-verify", "-verbose", "-certs", aab], output / "aab-signature.txt")
    require("jar verified" in jar.lower() and "jar contains unsigned entries" not in jar.lower(), "AAB must have a valid complete JAR signature")
    certificate_digest(run_tool(["keytool", "-printcert", "-jarfile", aab], output / "aab-certificate.txt"))
    certificate_digest(run_tool(["keytool", "-printcert", "-file", regular_file(payload, "publiccertificate.pem")], output / "expected-public-certificate.txt"))
    bundletool = Path(os.environ["RUNNER_TEMP"]) / "home-session-public-bundletool.jar"
    urllib.request.urlretrieve(f"https://github.com/google/bundletool/releases/download/{BUNDLETOOL_VERSION}/bundletool-all-{BUNDLETOOL_VERSION}.jar", bundletool)
    require(sha256(bundletool.read_bytes()) == BUNDLETOOL_SHA256, "Pinned bundletool hash mismatch")
    run_tool(["java", "-jar", bundletool, "validate", f"--bundle={aab}"], output / "aab-validation.txt")
    run_tool(["java", "-jar", bundletool, "dump", "manifest", f"--bundle={aab}", "--module=base"], output / "aab-manifest.xml")
    badging = run_tool([tools / "aapt", "dump", "badging", apk], output / "package.txt")
    xmltree = run_tool([tools / "aapt", "dump", "xmltree", apk, "AndroidManifest.xml"], output / "merged-manifest.txt")
    apk_metadata(badging, xmltree)
    aab_metadata(output / "aab-manifest.xml")
    dex_proof = []
    for kind, archive_path, pattern in (("apk", apk, r"classes\d*\.dex"), ("aab", aab, r"base/dex/classes\d*\.dex")):
        with zipfile.ZipFile(archive_path) as archive:
            names = [name for name in archive.namelist() if re.fullmatch(pattern, name)]
            require(names, "Release bytecode is missing")
            bytecode = b"".join(archive.read(name) for name in names)
            require(BANNER.encode() in bytecode and b"demo-banner-yandex" not in bytecode, "Release bytecode must contain the real R-M banner and omit the debug demo literal")
            dex_proof.append({"artifact": kind, "dex_sha256": sha256(bytecode), "real_banner_literal_present": True, "demo_banner_literal_absent": True})
    declared_gradle = regular_file(source, "app/build.gradle.kts").read_text()
    require('implementation("com.yandex.android:mobileads:8.5.0")' in declared_gradle, "Approved source must declare Yandex Mobile Ads 8.5.0")
    native = {"apk": verify_elf(apk), "aab": verify_elf(aab)}
    # Reuse the approved immutable native checker as an independent APK cross-check.
    run_tool(["python3", source / "scripts/verify-elf-alignment.py", apk], output / "native-page-alignment.txt")
    run_id = os.environ.get("GITHUB_RUN_ID", "")
    require(re.fullmatch(r"[1-9][0-9]*", run_id), "Invalid current verification run ID")
    current_sha = run_tool(["git", "-C", current, "rev-parse", "HEAD"]).strip()
    require(current_sha == os.environ["GITHUB_SHA"], "Current public payload checkout mismatch")
    provenance = {"repository": REPOSITORY, "source_sha": manifest["source_sha"],
                  "method": "local", "build_method": "local", "signing_method": "local",
                  "verification_method": "github_actions_public_artifacts", "built_signed_in_github_actions": False,
                  "verification_run_url": f"https://github.com/{REPOSITORY}/actions/runs/{run_id}",
                  "verification_commit_sha": current_sha, "verified_at": datetime.now(timezone.utc).isoformat(),
                  "source_proof": source_proof, "quality_gates": quality,
                  "local_build_environment": manifest["build_environment"],
                  "local_build_environment_evidence": "declared_by_local_build_receipt_not_independently_reproduced",
                  "package": PACKAGE, "version_code": 1, "version_name": "1.0.0", "min_sdk": 24, "target_sdk": 36, "compile_sdk": 36,
                  "upload_certificate_sha256": CERTIFICATE, "banner_id": BANNER,
                  "yandex_sdk_declared_in_approved_source": "8.5.0", "binary_source_reproducibility_claimed": False,
                  "bundletool": {"version": BUNDLETOOL_VERSION, "sha256": BUNDLETOOL_SHA256},
                  "artifacts": manifest["artifacts"], "dex_proof": dex_proof, "native_64bit_libraries": native,
                  "gates": {"source_bytes_and_git_blobs": True, "prior_ci_4_jobs": True, "unit_51_xml_and_receipt": True,
                            "apk_signature": True, "aab_signature": True, "public_certificate_exact": True,
                            "bundle_validation": True, "package_version_sdk": True, "debuggable_false": True,
                            "sdk_automatic_initialization_false": True, "advertising_id_permission_absent": True,
                            "real_banner_and_no_demo_literal": True, "apk_zipalign_16kb": True, "native_64bit_16kb": True},
                  "google_play_production_verified": False, "paid_ads_delivery_verified": False}
    write_json(output / "artifact-verification-provenance.json", provenance)
    for path in (apk, aab, regular_file(payload, "publiccertificate.pem"), regular_file(payload, "source-manifest.json")):
        shutil.copyfile(path, output / path.name)
    (output / "SHA256SUMS").write_text("".join(f"{item['sha256']}  {item['path']}\n" for item in manifest["artifacts"].values()))
    (output / "release-notes.md").write_text(
        "Home Session 1.0.0 (1): offline rooms, editable recurring tasks, 5/15/30-minute sessions, explicit completion/undo and dated history.\n\n"
        f"Built and signed locally. Independently verified public APK/AAB in GitHub Actions: {provenance['verification_run_url']}.\n"
        f"Approved source: {manifest['source_sha']}. This prerelease is not Google Play production and does not confirm paid ad delivery.\n")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(f"Public APK/AAB verified. Build/signing method: **local**. [Verification run]({provenance['verification_run_url']}).\n\n")
            summary.write(f"Source `{manifest['source_sha']}` · 58 matching source blobs · 51 passing actual unit XML cases · API 24/36 quality jobs passed.\n\n")
            summary.write("Signature, certificate, package/version/SDK, SDK initialization, real banner and 16 KB checks passed. Play production and paid delivery remain unverified.\n")


def publish(output):
    provenance = read_json(output / "artifact-verification-provenance.json")
    require(all(provenance["gates"].values()) and provenance["build_method"] == "local" and provenance["built_signed_in_github_actions"] is False, "Only verified local artifacts may be published")
    tag = f"v1.0.0-1-local-verification-{os.environ['GITHUB_RUN_ID']}"
    files = sorted(path for path in output.iterdir() if path.is_file())
    run_tool(["gh", "release", "create", tag, *files, "--repo", REPOSITORY, "--target", os.environ["GITHUB_SHA"],
              "--title", "Home Session 1.0.0 (1) · locally signed, publicly verified", "--prerelease", "--notes-file", output / "release-notes.md"])
    release = json.loads(run_tool(["gh", "release", "view", tag, "--repo", REPOSITORY, "--json", "url,isPrerelease,tagName,assets"]))
    require(release["isPrerelease"] and release["tagName"] == tag, "Published GitHub release state mismatch")
    write_json(output / "public-prerelease-receipt.json", {"url": release["url"], "tag": tag,
                "is_prerelease": True, "build_method": "local", "verification_run_url": provenance["verification_run_url"],
                "assets": release["assets"], "google_play_production_verified": False})
    run_tool(["gh", "release", "upload", tag, output / "public-prerelease-receipt.json", "--repo", REPOSITORY])
    print(release["url"])


def ui_bounds(node):
    match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    require(match is not None, "UI node has invalid bounds")
    return tuple(int(value) for value in match.groups())


def offline_network_state(airplane, setting, wifi, mobile, connectivity):
    return (airplane.strip() == "enabled" and setting.strip() == "1" and
            wifi.strip() == "0" and mobile.strip() == "0" and
            re.search(r"^\s*Active default network:\s*none\s*$", connectivity, re.MULTILINE) is not None)


class ReleaseUiCapture:
    """Use the installed release UI only: native hierarchy, input taps/swipes and raw screencap."""
    def __init__(self, output):
        self.output = output
        self.adb = Path(os.environ["ANDROID_HOME"]) / "platform-tools" / "adb"
        self.actions, self.commands, self.images, self.network_checks = [], [], [], []
        self.locale = None
        self.screen = (0, 0)
        self.deadline = time.monotonic() + 15 * 60

    def command(self, *arguments, timeout=20):
        start = time.monotonic()
        remaining = self.deadline - start
        require(remaining > 0, "Release UI capture exceeded its 15-minute command deadline")
        result = subprocess.run([str(self.adb), *map(str, arguments)], check=False,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=min(timeout, remaining))
        self.commands.append({"arguments": list(map(str, arguments)), "returncode": result.returncode,
                              "elapsed_seconds": round(time.monotonic() - start, 3)})
        require(result.returncode == 0, "ADB capture command failed: " + " ".join(map(str, arguments)) +
                "\n" + result.stderr.decode("utf-8", errors="replace")[-2000:])
        return result.stdout

    def shell(self, *arguments, timeout=20):
        return self.command("shell", *arguments, timeout=timeout).decode("utf-8", errors="replace")

    def network(self, label, wait=False):
        # AOSP API 36 shell commands; only emulator radios change, never the app or its ad views.
        # https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/main/service/src/com/android/server/ConnectivityService.java
        attempts = 20 if wait else 1
        for attempt in range(attempts):
            state = {"airplane_mode": self.shell("cmd", "connectivity", "airplane-mode"),
                     "airplane_setting": self.shell("settings", "get", "global", "airplane_mode_on"),
                     "wifi_setting": self.shell("settings", "get", "global", "wifi_on"),
                     "mobile_data_setting": self.shell("settings", "get", "global", "mobile_data"),
                     "connectivity": self.shell("dumpsys", "connectivity")}
            if offline_network_state(*state.values()):
                state["label"] = label
                state["checked_at"] = datetime.now(timezone.utc).isoformat()
                self.network_checks.append(state)
                write_json(self.output / "offline-network-evidence.json", self.network_checks)
                return
            if attempt + 1 < attempts:
                time.sleep(0.5)
        write_json(self.output / "offline-network-failure.json", state)
        require(False, "API 36 radios/default network are not verified offline; app launch is forbidden")

    def dump(self):
        self.shell("uiautomator", "dump", "--compressed", "/sdcard/home-session-release-ui.xml", timeout=20)
        xml = self.command("exec-out", "cat", "/sdcard/home-session-release-ui.xml")
        return xml, ET.fromstring(xml)

    @staticmethod
    def signature(root):
        # System clock changes do not indicate app movement. App text and bounds must settle.
        return tuple(tuple(sorted(node.attrib.items())) for node in root.iter("node")
                     if node.get("package") == PACKAGE)

    def settled(self):
        previous = None
        for _ in range(8):
            xml, root = self.dump()
            signature = self.signature(root)
            if signature and signature == previous:
                time.sleep(0.35)
                return xml, root
            previous = signature
            time.sleep(0.35)
        require(False, "Release app hierarchy did not settle within eight bounded dumps")

    def visible(self, node):
        left, top, right, bottom = ui_bounds(node)
        width, height = self.screen
        return left >= 0 and top >= 0 and right <= width and bottom <= height and right > left and bottom > top

    def matches(self, root, resource=None, text=None):
        return [node for node in root.iter("node") if node.get("package") == PACKAGE and
                (resource is None or node.get("resource-id") == f"{PACKAGE}:id/{resource}") and
                (text is None or node.get("text") == text) and self.visible(node)]

    def scroll(self, root, direction):
        nodes = [node for node in root.iter("node") if node.get("package") == PACKAGE and
                 node.get("scrollable") == "true" and self.visible(node)]
        if not nodes:
            return False
        left, top, right, bottom = ui_bounds(nodes[0])
        x = (left + right) // 2
        low, high = top + (bottom - top) * 3 // 4, top + (bottom - top) // 4
        start, end = (low, high) if direction == "down" else (high, low)
        self.actions.append({"action": "swipe", "locale": self.locale, "direction": direction,
                             "container_bounds": nodes[0].get("bounds"), "from": [x, start], "to": [x, end]})
        self.shell("input", "swipe", x, start, x, end, 450)
        return True

    def find(self, resource=None, text=None, direction="down"):
        for _ in range(14):
            xml, root = self.settled()
            nodes = self.matches(root, resource, text)
            if nodes:
                return xml, root, nodes[0]
            require(self.scroll(root, direction), f"UI target missing with no scrollable container: {resource or text}")
        require(False, f"Visible release UI target missing: {resource or text}")

    def tap(self, resource=None, text=None, direction="down"):
        xml, root, node = self.find(resource, text, direction)
        require(node.get("enabled") == "true" and node.get("clickable") == "true", "Release control is not enabled/clickable")
        name = f"action-{len(self.actions):03d}-{self.locale}"
        (self.output / f"{name}.xml").write_bytes(xml)
        left, top, right, bottom = ui_bounds(node)
        point = [(left + right) // 2, (top + bottom) // 2]
        self.actions.append({"action": "tap", "locale": self.locale, "resource_id": node.get("resource-id"),
                             "text": node.get("text"), "content_description": node.get("content-desc"),
                             "bounds": node.get("bounds"), "point": point, "hierarchy": f"{name}.xml"})
        self.shell("input", "tap", *point)
        return node

    def top(self):
        previous = None
        for _ in range(14):
            _, root = self.settled()
            signature = self.signature(root)
            if signature == previous:
                return
            previous = signature
            if not self.scroll(root, "up"):
                return
        require(False, "Native release screen did not reach its top")

    def page(self, title):
        _, root = self.settled()
        require(self.matches(root, "screen_title", title), f"Actual release page/locale mismatch: {title}")

    def capture(self, name, title):
        self.top()
        self.page(title)
        self.network(name)
        xml, root = self.settled()
        require(self.matches(root, "ad_label"), "The app's ordinary ad footer must remain visible in release captures")
        require(not any("demo ad" in (node.get("text", "") + node.get("content-desc", "")).lower()
                        for node in root.iter("node")), "A demo ad must not appear in release evidence")
        image = self.command("exec-out", "screencap", "-p")
        require(image.startswith(b"\x89PNG\r\n\x1a\n"), "Device screencap is not a raw PNG")
        (self.output / f"{name}.png").write_bytes(image)
        (self.output / f"{name}.xml").write_bytes(xml)
        self.images.append({"locale": self.locale, "page_title": title, "png": f"{name}.png",
                            "png_sha256": sha256(image), "hierarchy": f"{name}.xml", "hierarchy_sha256": sha256(xml)})


def capture_release_ui(payload, verified, output, manifest):
    require(verified is not None, "Successful public artifact verification proof is required before screenshots")
    proof = read_json(verified)
    run_url = f"https://github.com/{REPOSITORY}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    require(proof.get("verification_run_url") == run_url and proof.get("verification_commit_sha") == os.environ["GITHUB_SHA"] and
            proof.get("source_sha") == manifest["source_sha"] and proof.get("artifacts") == manifest["artifacts"] and
            proof.get("upload_certificate_sha256") == CERTIFICATE and proof.get("banner_id") == BANNER and
            proof.get("build_method") == "local" and proof.get("built_signed_in_github_actions") is False and
            isinstance(proof.get("gates"), dict) and proof["gates"] and all(value is True for value in proof["gates"].values()),
            "Screenshots must use this run's independently verified public release APK")
    capture = ReleaseUiCapture(output)
    completed, status = [], "failed"
    try:
        capture.command("wait-for-device", timeout=60)
        require(capture.shell("getprop", "ro.build.version.sdk").strip() == "36", "Release screenshots require real API 36")
        capture.shell("cmd", "connectivity", "airplane-mode", "enable")
        capture.shell("svc", "wifi", "disable")
        # The base setting and svc pair also covers a stale/null subscription-specific setting.
        # https://android.googlesource.com/platform/tools/test/connectivity/+/main/acts/framework/acts/controllers/cellular_lib/AndroidCellularDut.py
        capture.shell("settings", "put", "global", "mobile_data", "0")
        capture.shell("svc", "data", "disable")
        capture.network("before-install-and-first-launch", wait=True)
        for setting, value in (("screen_off_timeout", "1800000"), ("font_scale", "1.0")):
            capture.shell("settings", "put", "system", setting, value)
        capture.shell("svc", "power", "stayon", "true")
        capture.shell("input", "keyevent", "224")
        capture.shell("wm", "dismiss-keyguard")
        size = capture.shell("wm", "size")
        dimensions = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", size)
        require(dimensions, "Actual display size is missing")
        capture.screen = tuple(map(int, dimensions[-1]))
        apk = regular_file(payload, manifest["artifacts"]["apk"]["path"])
        require(sha256(apk.read_bytes()) == manifest["artifacts"]["apk"]["sha256"], "APK changed before installation")
        require("Success" in capture.command("install", "-r", apk, timeout=120).decode(), "Release APK installation failed")
        package = capture.shell("dumpsys", "package", PACKAGE)
        (output / "installed-release-package.txt").write_text(package)
        require(re.search(r"versionCode=1\s", package) and "versionName=1.0.0" in package and
                re.search(r"(?:pkgFlags|flags)=\[", package) and not re.search(r"\bDEBUGGABLE\b", package),
                "Installed release must have version 1.0.0 (1) and no DEBUGGABLE flag")
        installed_paths = capture.shell("pm", "path", PACKAGE).strip().splitlines()
        require(len(installed_paths) == 1 and re.fullmatch(r"package:/data/app/[A-Za-z0-9_+=~/.-]+/base\.apk", installed_paths[0]),
                "Unexpected installed release APK path")
        installed_hash = capture.shell("sha256sum", installed_paths[0][len("package:"):]).split()[0]
        require(installed_hash == manifest["artifacts"]["apk"]["sha256"], "Installed APK bytes differ from verified public APK")
        write_json(output / "installed-release-proof.json", {"apk_sha256": installed_hash, "debuggable": False,
                   "upload_certificate_sha256": CERTIFICATE, "signature_evidence": "same_APK_bytes_as_independent_prior_job"})
        for language, labels in (("ru", ("Дом за 15 минут", "Предложенная сессия", "Ваша сессия уборки", "Журнал выполнения", "Контекстная реклама", "Выполнить: ")),
                                 ("en", ("Home in 15 Minutes", "Your suggested session", "Your cleaning session", "Completion journal", "Contextual ads", "Complete: "))):
            capture.locale = language
            home, plan, session, history, contextual, completion_prefix = labels
            require("Success" in capture.shell("pm", "clear", PACKAGE), "Cannot clear real app data between languages")
            # API 36 LocaleManagerShellCommand documents this argument order and language-tag form.
            # https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r4/services/core/java/com/android/server/locales/LocaleManagerShellCommand.java
            capture.shell("cmd", "locale", "set-app-locales", PACKAGE, "--user", "0", "--locales", language)
            locale_result = capture.shell("cmd", "locale", "get-app-locales", PACKAGE, "--user", "0")
            (output / f"{language}-actual-app-locales.txt").write_text(locale_result)
            require(re.search(r"\[" + language + r"\]", locale_result), "Android did not apply the requested app locale")
            capture.shell("am", "force-stop", PACKAGE)
            capture.network(f"{language}-before-first-launch")
            launch = capture.shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity", timeout=30)
            (output / f"{language}-fresh-process-launch.txt").write_text(launch)
            require("Status: ok" in launch, "Release activity did not launch successfully")
            capture.tap(text=contextual)
            capture.tap(resource="onboarding_templates")
            capture.capture(f"{language}-01-home", home)
            capture.tap(resource="home_pick_15")
            capture.capture(f"{language}-02-plan-15", plan)
            capture.tap(resource="plan_start")
            capture.capture(f"{language}-03-session", session)
            node = capture.tap(resource="session_complete")
            description = node.get("content-desc", "")
            require(description.startswith(completion_prefix), "Actual task completion description is missing")
            task_title = description[len(completion_prefix):]
            require(task_title in TEMPLATE_TASK_TITLES[language], "Actual completed task must be an original template in the requested language")
            capture.find(resource="session_undo", direction="up")
            capture.capture(f"{language}-04-session-completed", session)
            capture.tap(resource="toolbar_back", direction="up")
            capture.page(home)
            capture.tap(resource="home_history")
            capture.top()
            capture.page(history)
            _, root = capture.settled()
            require(capture.matches(root, text=task_title), "Explicitly completed task is missing from real journal")
            capture.capture(f"{language}-05-history", history)
            completed.append({"locale": language, "task_title": task_title, "completion_method": "visible_session_complete_button",
                              "templates_method": "visible_original_examples_button", "ad_choice": "contextual"})
        status = "success"
    except BaseException:
        # Preserve the actual active screen before any teardown, including a failed native UI step.
        capture.deadline = time.monotonic() + 45
        try:
            (output / "failure-screen.png").write_bytes(capture.command("exec-out", "screencap", "-p"))
            xml, _ = capture.dump()
            (output / "failure-screen.xml").write_bytes(xml)
        except BaseException:
            pass
        raise
    finally:
        write_json(output / "ui-actions.json", capture.actions)
        write_json(output / "adb-command-events.json", capture.commands)
        write_json(output / "release-ui-capture-receipt.json", {
            "status": status, "repository": REPOSITORY, "source_sha": manifest["source_sha"],
            "verification_run_url": run_url, "verification_commit_sha": os.environ["GITHUB_SHA"],
            "artifact_verification_provenance_sha256": sha256(verified.read_bytes()),
            "build_method": "local", "capture_method": "github_actions_API36_native_UI_and_raw_adb_screencap",
            "apk_sha256": manifest["artifacts"]["apk"]["sha256"], "banner_id_in_verified_release": BANNER,
            "offline_before_first_app_launch_and_at_each_capture": True if status == "success" else None,
            "device_connectivity_only_disabled": True, "app_ad_code_or_pixels_modified": False,
            "persisted_fixtures_injected": False, "images_edited": False, "screen_size": capture.screen,
            "local_build_environment": manifest["build_environment"],
            "local_build_environment_evidence": "declared_by_local_build_receipt_not_independently_reproduced",
            "locales": completed, "screenshots": capture.images,
            "already_green_unit_and_device_cases_repeated": False,
            "google_play_production_verified": False, "paid_ads_delivery_verified": False})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("inputs", "quality", "artifacts", "publish", "screenshots"))
    parser.add_argument("--payload", type=Path, required=True)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--current", type=Path)
    parser.add_argument("--verified", type=Path)
    parser.add_argument("--output", type=Path, default=Path("verification-output"))
    args = parser.parse_args()
    manifest = contract(args.payload)
    if args.phase == "inputs":
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write(f"source_sha={manifest['source_sha']}\n")
        return
    args.output.mkdir(parents=True, exist_ok=True)
    if args.phase == "quality":
        require(args.source is not None and args.current is not None, "Source and current checkouts are required")
        write_json(args.output / "source-proof.json", verify_source(args.payload, args.source, args.current, manifest))
        write_json(args.output / "quality-gates.json", verify_ci(manifest, args.output))
    elif args.phase == "artifacts":
        require(args.source is not None and args.current is not None, "Source and current checkouts are required")
        verify_artifacts(args.payload, args.source, args.current, args.output, manifest)
    elif args.phase == "screenshots":
        capture_release_ui(args.payload, args.verified, args.output, manifest)
    else:
        publish(args.output)


if __name__ == "__main__":
    main()
