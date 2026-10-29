#!/usr/bin/env python3
"""Build non-debuggable release and sign with the preserved migration lineage.
Never publishes. Copy/backup .local/signing securely before cleaning this checkout.
"""
from pathlib import Path
import os, subprocess, sys

root = Path(__file__).resolve().parent.parent
env = dict(os.environ)
env.setdefault("JAVA_HOME", "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home")
subprocess.run(
    [str(root / "gradlew"), ":app:assembleRelease", "--no-daemon"], cwd=root, env=env, check=True
)
subprocess.run(
    [
        sys.executable,
        str(root / "tools/sign-release.py"),
        "--apk",
        str(root / "app/build/outputs/apk/release/app-release-unsigned.apk"),
        "--out",
        str(root / "app/build/outputs/apk/release/app-release.apk"),
    ],
    cwd=root,
    env=env,
    check=True,
)
