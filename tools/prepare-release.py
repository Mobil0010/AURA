#!/usr/bin/env python3
"""Package an already-built APK and update manifest for public GitHub Releases."""
import argparse, hashlib, json, re, shutil
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("--apk", default="app/build/outputs/apk/release/app-release.apk")
p.add_argument("--bridge", help="Required after signing migration: frozen old-key bridge APK")
p.add_argument("--repo", default="Mobil0010/AURA")
p.add_argument(
    "--notes",
    default="새 버전 확인, APK 다운로드 및 검증, 안드로이드 설치 화면 연결을 추가했습니다.",
)
a = p.parse_args()
root = Path(__file__).resolve().parent.parent
source = Path(a.apk)
if not source.is_absolute():
    source = root / source
config = (root / "app/build.gradle").read_text()
code = int(re.search(r"versionCode\s+(\d+)", config)[1])
name = re.search(r"versionName\s+'([^']+)'", config)[1]
if not re.fullmatch(r"[\w.-]+/[\w.-]+", a.repo):
    p.error("Invalid repository name")
if code >= 24:
    import subprocess, os, ssl

    sdk = root / ".local/android-sdk/build-tools/36.0.0"
    env = dict(os.environ)
    env.setdefault("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home")
    signed = subprocess.check_output(
        [str(sdk / "apksigner"), "verify", "--verbose", "--print-certs", str(source)],
        text=True,
        env=env,
    )
    cert = ssl.PEM_cert_to_DER_cert((root / "docs/signing/release.pem").read_text())
    expected = hashlib.sha256(cert).hexdigest()
    assert (
        "Verified using v3.1 scheme (APK Signature Scheme v3.1): true" in signed
        and expected in signed
    ), "Release rotation signature required"
    flags = subprocess.check_output([str(sdk / "aapt"), "dump", "badging", str(source)], text=True)
    assert "application-debuggable" not in flags, "Debug APK cannot be released"
    assert (
        f"versionCode='{code}'" in flags and f"versionName='{name}'" in flags
    ), "APK/config versions differ"
out = root / "dist" / f"v{name}"
out.mkdir(parents=True, exist_ok=True)
apk = out / f"AURA-{name}.apk"
shutil.copyfile(source, apk)
manifest = {
    "versionCode": code,
    "versionName": name,
    "apkUrl": f"https://github.com/{a.repo}/releases/download/v{name}/{apk.name}",
    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "size": apk.stat().st_size,
    "minSdk": 26,
    "notes": a.notes,
}
if code >= 24 and not a.bridge:
    p.error("Signing migration requires --bridge; legacy clients must retain their compatible feed")
if a.bridge:
    bridge = Path(a.bridge)
    bridge_name = "AURA-0.18.1-bridge.apk"
    shutil.copyfile(bridge, out / bridge_name)
    import subprocess

    sdk = root / ".local/android-sdk/build-tools/36.0.0"
    badging = subprocess.check_output(
        [str(sdk / "aapt"), "dump", "badging", str(bridge)], text=True
    )
    assert (
        "versionCode='23'" in badging and "versionName='0.18.1'" in badging
    ), "Unexpected bridge version"
    frozen = json.loads((root / "docs/signing/bridge.json").read_text())
    assert (
        hashlib.sha256(bridge.read_bytes()).hexdigest() == frozen["sha256"]
        and bridge.stat().st_size == frozen["size"]
    ), "Bridge differs from frozen migration APK"
    current = dict(manifest)
    legacy = {
        **manifest,
        "versionCode": 23,
        "versionName": "0.18.1",
        "apkUrl": f"https://github.com/{a.repo}/releases/download/v{name}/{bridge_name}",
        "sha256": hashlib.sha256(bridge.read_bytes()).hexdigest(),
        "size": bridge.stat().st_size,
        "notes": "서명 전환을 위한 호환 업데이트입니다. 설치 후 앱을 다시 열고 업데이트를 한 번 더 확인하여 최신 버전을 설치해 주세요. 앱을 삭제할 필요 없이 라이브러리와 설정을 유지합니다.",
    }
    (out / "update-v2.json").write_text(json.dumps(current, ensure_ascii=False, indent=2) + "\n")
    (out / "update.json").write_text(json.dumps(legacy, ensure_ascii=False, indent=2) + "\n")
else:
    (out / "update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
(out / "SHA256SUMS.txt").write_text(f'{manifest["sha256"]}  {apk.name}\n')
if a.bridge:
    with (out / "SHA256SUMS.txt").open("a") as sums:
        sums.write(f"{legacy['sha256']}  {bridge_name}\n")
print(out)

# Include corresponding buildable sources and native licenses, never local credentials or SDKs.
import zipfile

source_archive = out / f"AURA-{name}-source.zip"
with zipfile.ZipFile(source_archive, "w", zipfile.ZIP_DEFLATED) as archive:
    for entry in [
        "AGENTS.md",
        ".gitignore",
        ".github/readme",
        "app",
        "docs",
        "gradle",
        "tools",
        "vendor",
        "README.md",
        "build.gradle",
        "settings.gradle",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
    ]:
        item = root / entry
        if not item.exists():
            continue
        for path in item.rglob("*") if item.is_dir() else [item]:
            relative = path.relative_to(root)
            if (
                not path.is_file()
                or path.is_symlink()
                or any(part in {"build", ".cxx", ".git", "__pycache__"} for part in relative.parts)
                or path.suffix
                in {".jks", ".keystore", ".idsig", ".pass", ".p12", ".pfx", ".pk8", ".key"}
            ):
                continue
            archive.write(path, f"AURA-{name}/" + str(relative))
