#!/usr/bin/env python3
"""Sign a release APK using Android 13+ rotation and legacy Android compatibility.
Private material stays in ignored .local/signing (mode 0700); never publish it.
"""
import argparse, os, secrets, shutil, subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser()
p.add_argument("--apk", required=True)
p.add_argument("--out", required=True)
p.add_argument("--legacy-only", action="store_true")
a = p.parse_args()
java = Path(
    os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home")
)
env = {**os.environ, "JAVA_HOME": str(java)}
sdk = Path(os.environ.get("ANDROID_SDK_ROOT", root / ".local/android-sdk"))
signer = sdk / "build-tools/36.0.0/apksigner"
private = root / ".local/signing"
private.mkdir(parents=True, exist_ok=True)
private.chmod(0o700)
legacy = private / "legacy.jks"
oldpass = private / "legacy.pass"
key = private / "release.jks"
password = private / "release.pass"
lineage = private / "aura.lineage"


def run(args):
    subprocess.run([str(x) for x in args], check=True, env=env, stdout=subprocess.DEVNULL)


if not legacy.exists():
    shutil.copyfile(Path.home() / ".android/debug.keystore", legacy)
    legacy.chmod(0o600)
if not oldpass.exists():
    oldpass.write_text("android\n")
    oldpass.chmod(0o600)
if not key.exists():
    if (root / "docs/signing/release.pem").exists():
        raise SystemExit(
            "Registered release key is missing. Restore .local/signing from secure backup; do not generate a replacement."
        )
    if password.exists():
        raise SystemExit("Password exists without release key; refusing to replace identity")
    password.write_text(secrets.token_urlsafe(48) + "\n")
    password.chmod(0o600)
    run(
        [
            java / "bin/keytool",
            "-genkeypair",
            "-keystore",
            key,
            "-storetype",
            "JKS",
            "-alias",
            "aura-release",
            "-keyalg",
            "RSA",
            "-keysize",
            "4096",
            "-sigalg",
            "SHA256withRSA",
            "-validity",
            "10000",
            "-dname",
            "CN=AURA Release, O=AURA",
            "-storepass:file",
            password,
            "-keypass:file",
            password,
        ]
    )
    key.chmod(0o600)
old = ["--ks", legacy, "--ks-key-alias", "androiddebugkey", "--ks-pass", "file:" + str(oldpass)]
new = ["--ks", key, "--ks-key-alias", "aura-release", "--ks-pass", "file:" + str(password)]
if not lineage.exists():
    run([signer, "rotate", "--out", lineage, "--old-signer", *old, "--new-signer", *new])
    lineage.chmod(0o600)
public = root / "docs/signing"
public.mkdir(parents=True, exist_ok=True)
for args, name in [(old, "legacy.pem"), (new, "release.pem")]:
    k = args[1]
    alias = args[3]
    passfile = args[5][5:]
    candidate = private / ("export-" + name)
    run(
        [
            java / "bin/keytool",
            "-exportcert",
            "-rfc",
            "-keystore",
            k,
            "-alias",
            alias,
            "-storepass:file",
            passfile,
            "-file",
            candidate,
        ]
    )
    if (public / name).exists() and (public / name).read_bytes() != candidate.read_bytes():
        raise SystemExit(
            "Signing certificate differs from registered public identity; refusing to replace it"
        )
    shutil.copyfile(candidate, public / name)
    candidate.unlink()
shutil.copyfile(
    lineage, public / "aura.lineage"
)  # public certificates and proofs only; no private keys
out = Path(a.out)
out.parent.mkdir(parents=True, exist_ok=True)
options = (
    old
    if a.legacy_only
    else [*old, "--next-signer", *new, "--lineage", lineage, "--rotation-min-sdk-version", "33"]
)
run([signer, "sign", "--v4-signing-enabled", "false", "--out", out, *options, a.apk])
run([signer, "verify", "--verbose", out])
print(
    "Signed:",
    out,
    "(legacy bridge)" if a.legacy_only else "(Android 13+ release key; Android 8–12 legacy key)",
)
