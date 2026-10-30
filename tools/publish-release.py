#!/usr/bin/env python3
"""Publish locally verified assets using Git's existing GitHub credential helper.
No credential is printed, written to disk, or sent outside api/uploads.github.com.
"""
import argparse, hashlib, json, mimetypes, os, ssl, subprocess, sys
from pathlib import Path
from urllib.request import Request, build_opener, HTTPRedirectHandler, HTTPSHandler
from urllib.error import HTTPError
from urllib.parse import urlparse, urlencode


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


parser = argparse.ArgumentParser()
parser.add_argument("--directory", default="dist/v0.4.0")
parser.add_argument("--repo", default="Mobil0010/AURA")
parser.add_argument("--title", help="Release title; defaults to AURA and the current version")
parser.add_argument(
    "--publish", action="store_true", help="Publish after all asset uploads succeed"
)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
directory = Path(args.directory)
if not directory.is_absolute():
    directory = root / directory
metadata = json.loads(
    (
        directory / ("update-v2.json" if (directory / "update-v2.json").exists() else "update.json")
    ).read_text(encoding="utf-8")
)
version = metadata["versionName"]
tag = "v" + version
apk = directory / f"AURA-{version}.apk"
assets = [
    apk,
    directory / "update.json",
    directory / "SHA256SUMS.txt",
    directory / f"AURA-{version}-source.zip",
]
if (directory / "update-v2.json").exists():
    legacy = json.loads((directory / "update.json").read_text(encoding="utf-8"))
    bridge_name = "AURA-0.18.1-bridge.apk"
    bridge = directory / bridge_name
    assert legacy["versionCode"] == 23 and legacy["versionName"] == "0.18.1"
    assert (
        legacy["apkUrl"] == f"https://github.com/{args.repo}/releases/download/{tag}/{bridge_name}"
    )
    assert (
        bridge.stat().st_size == legacy["size"]
        and hashlib.sha256(bridge.read_bytes()).hexdigest() == legacy["sha256"]
    )
    assets += [directory / "update-v2.json", bridge]
for file in assets:
    if not file.is_file():
        raise SystemExit(f"Missing asset: {file.name}")
assert apk.stat().st_size == metadata["size"], "APK size mismatch"
assert hashlib.sha256(apk.read_bytes()).hexdigest() == metadata["sha256"], "APK checksum mismatch"
assert metadata["apkUrl"] == f"https://github.com/{args.repo}/releases/download/{tag}/{apk.name}"

token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
if not token:
    result = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        text=True,
        capture_output=True,
        env={**os.environ, "GIT_TERMINAL_PROMPT": "0"},
        timeout=30,
    )
    fields = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    token = fields.get("password")
if not token:
    raise SystemExit("No existing GitHub credential is available.")
try:
    import certifi

    tls = ssl.create_default_context(cafile=certifi.where())
except ImportError:
    tls = ssl.create_default_context()
opener = build_opener(NoRedirect(), HTTPSHandler(context=tls))


def api(method, url, data=None, content_type="application/json"):
    parsed = urlparse(url)
    if (
        parsed.scheme != "https"
        or parsed.hostname not in {"api.github.com", "uploads.github.com"}
        or parsed.username
        or parsed.password
    ):
        raise RuntimeError("Refusing an unexpected GitHub API host")
    if isinstance(data, dict):
        data = json.dumps(data, ensure_ascii=False).encode()
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "AURA-release-publisher",
        "Content-Type": content_type,
    }
    with opener.open(
        Request(url, data=data, headers=headers, method=method), timeout=180
    ) as response:
        body = response.read()
        return json.loads(body) if body else {}


base = f"https://api.github.com/repos/{args.repo}"
try:
    repo = api("GET", base)
    if not repo.get("permissions", {}).get("push"):
        raise RuntimeError("The saved account cannot publish to this repository")
    try:
        release = api("GET", base + "/releases/tags/" + tag)
    except HTTPError as error:
        if error.code != 404:
            raise
        notes = f"""AURA {version} 개발 버전입니다.

{metadata.get('notes','')}

Android 8 이상용 universal APK입니다. 기존 설치와의 서명 호환성을 유지합니다. 새 서명 전환 시 전환용 업데이트를 먼저 설치해 주세요. 안드로이드의 마지막 설치 확인은 직접 진행해 주세요.

APK: {apk.name}
버전 정보: update-v2.json (서명 전환 이후) / update.json (기존 설치 전환용)
대응 소스·빌드 안내·네이티브 라이선스: AURA-{version}-source.zip
"""
        release = api(
            "POST",
            base + "/releases",
            {
                "tag_name": tag,
                "target_commitish": repo["default_branch"],
                "name": args.title or "AURA " + version,
                "body": notes,
                "draft": True,
                "prerelease": False,
            },
        )
        print("Created draft release:", release["html_url"], flush=True)
    if not release.get("draft"):
        raise RuntimeError("This release is already published; refusing to modify it")
    existing = {a["name"]: a for a in release.get("assets", [])}
    upload = release["upload_url"].split("{")[0]
    for file in assets:
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        if file.name in existing:
            prior = existing[file.name]
            if (
                prior.get("size") == file.stat().st_size
                and prior.get("digest") == "sha256:" + digest
            ):
                print("Already uploaded:", file.name, flush=True)
                continue
            raise RuntimeError(
                "An asset with different or unverified contents already exists: " + file.name
            )
        mime = (
            "application/vnd.android.package-archive"
            if file.suffix == ".apk"
            else mimetypes.guess_type(file.name)[0] or "application/octet-stream"
        )
        uploaded = api(
            "POST", upload + "?" + urlencode({"name": file.name}), file.read_bytes(), mime
        )
        if uploaded.get("size") != file.stat().st_size or uploaded.get("state") != "uploaded":
            raise RuntimeError("Upload verification failed: " + file.name)
        if uploaded.get("digest") and uploaded["digest"] != "sha256:" + digest:
            raise RuntimeError("Remote checksum mismatch: " + file.name)
        print("Uploaded:", file.name, flush=True)
    refreshed = api("GET", base + "/releases/" + str(release["id"]))
    remote = {a["name"]: a for a in refreshed["assets"]}
    for file in assets:
        assert file.name in remote and remote[file.name]["size"] == file.stat().st_size
    if args.publish:
        refreshed = api(
            "PATCH",
            base + "/releases/" + str(release["id"]),
            {"draft": False, "make_latest": "true"},
        )
        if refreshed.get("draft"):
            raise RuntimeError("Release remained a draft")
        print("Published:", refreshed["html_url"], flush=True)
    else:
        print("Draft ready:", refreshed["html_url"], flush=True)
except HTTPError as error:
    print(f"GitHub API request failed: HTTP {error.code}", file=sys.stderr)
    sys.exit(1)
except Exception as error:
    print(str(error), file=sys.stderr)
    sys.exit(1)
