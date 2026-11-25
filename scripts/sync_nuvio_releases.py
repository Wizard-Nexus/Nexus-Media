"""Bring published Nuvio TV releases into the corresponding Nexus Media branch."""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from release_beta import parse_versions, updated_build_file

UPSTREAM = "NuvioMedia/NuvioTV"
FORK = "Wizard-Nexus/Nexus-Media"
ASSET = "Nexus-Media.apk"
BUILD_FILE = Path("app/build.gradle.kts")
SCRIPTS = Path(__file__).resolve().parent
PROGRAM_FILES = Path(os.environ.get("ProgramFiles", r"C:\Program Files"))
GIT = PROGRAM_FILES / "Git/cmd/git.exe"
GH = PROGRAM_FILES / "GitHub CLI/gh.exe"
POWERSHELL = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe"
VERSION_TAG = re.compile(r"v?(\d+)\.(\d+)\.(\d+)(?:-([a-zA-Z0-9]+(?:[.-][a-zA-Z0-9]+)*))?")
VERSION_LINE = re.compile(r'\s*version(?:Name\s*=\s*"[^"]+"|Code\s*=\s*\d+)\s*')
CONFLICT = re.compile(r"^<<<<<<<[^\n]*\n(.*?)^=======\n(.*?)^>>>>>>>[^\n]*\n", re.M | re.S)


class SyncError(Exception):
    """A release could not be prepared or advanced safely."""


def run(*args, cwd, check=True, stdout=subprocess.PIPE):
    result = subprocess.run(
        list(map(str, args)), cwd=cwd, stdin=subprocess.DEVNULL, stdout=stdout,
        stderr=subprocess.STDOUT, encoding="utf-8", errors="replace",
        creationflags=subprocess.CREATE_NO_WINDOW, timeout=5_400,
    )
    if check and result.returncode:
        raise SyncError((result.stdout or "See the verification log.").strip()[-2000:])
    return result


def git(cwd, *args, check=True):
    return run(
        GIT, "-c", "core.hooksPath=NUL",
        "-c", "credential.https://github.com.helper=",
        "-c", "credential.https://github.com.helper=!gh auth git-credential",
        *args, cwd=cwd, check=check,
    )


def api(repo, endpoint):
    return json.loads(run(GH, "api", endpoint, cwd=repo).stdout)


def version_key(tag):
    match = VERSION_TAG.fullmatch(tag)
    if not match:
        return None
    core = tuple(map(int, match.groups()[:3]))
    suffix = match[4]
    identifiers = tuple(
        (0, int(part)) if part.isdecimal() else (1, part.lower())
        for part in suffix.split(".")
    ) if suffix else ()
    return core, suffix is None, identifiers


def latest_beta(releases):
    eligible = [
        item for item in releases
        if item["prerelease"] and not item["draft"]
        and version_key(item["tag_name"]) is not None
        and not version_key(item["tag_name"])[1]
    ]
    return max(eligible, key=lambda item: version_key(item["tag_name"]), default=None)


def read_feed(repo):
    stable = api(repo, f"repos/{UPSTREAM}/releases/latest")
    stable_key = version_key(stable["tag_name"])
    if stable["draft"] or stable["prerelease"] or not stable_key or not stable_key[1]:
        raise SyncError("Nuvio's Stable feed did not identify a published Stable version.")
    beta = latest_beta(api(repo, f"repos/{UPSTREAM}/releases?per_page=100"))
    if beta and version_key(beta["tag_name"]) <= stable_key:
        beta = None
    return {"stable": stable, "beta": beta}


def ancestor(repo, older, newer):
    result = git(repo, "merge-base", "--is-ancestor", older, newer, check=False)
    if result.returncode > 1:
        raise SyncError(result.stdout)
    return result.returncode == 0


def remote_heads(repo):
    git(repo, "fetch", "--no-tags", "origin",
        "refs/heads/codex/stable:refs/remotes/origin/codex/stable",
        "refs/heads/codex/beta:refs/remotes/origin/codex/beta")
    return {name: git(repo, "rev-parse", f"origin/codex/{name}").stdout.strip()
            for name in ("stable", "beta")}


def fetch_tag(repo, owner, tag):
    if version_key(tag) is None:
        raise SyncError(f"Unsupported release tag {tag!r}.")
    target = f"refs/nexus/{owner}/{tag}"
    remote = "origin" if owner == "published" else f"https://github.com/{UPSTREAM}.git"
    git(repo, "fetch", "--no-tags", remote, f"refs/tags/{tag}:{target}")
    return git(repo, "rev-parse", f"{target}^{{commit}}").stdout.strip()


def next_version(repo, heads, published, channel):
    versions = [
        parse_versions(git(repo, "show", f"{head}:{BUILD_FILE.as_posix()}").stdout)
        for head in set(heads.values()) | set(published)
    ]
    keys = [version_key(name) for name, _ in versions]
    if any(key is None for key in keys):
        raise SyncError("A Nexus release has an unrecognized version.")
    major, minor, patch = max(key[0] for key in keys)
    suffix = "-beta.1" if channel == "beta" else ""
    return f"{major}.{minor}.{patch + 1}{suffix}", max(code for _, code in versions) + 1


def version_only_conflicts(contents):
    """Keep Nexus version assignments when a merge hunk only changes those assignments."""
    def replace(match):
        sides = (match[1].strip().splitlines(), match[2].strip().splitlines())
        if all(lines and all(VERSION_LINE.fullmatch(line) for line in lines) for lines in sides):
            return match[1]
        return match[0]

    return CONFLICT.sub(replace, contents)


def prepare_candidate(repo, workspace, base, source, channel, tag, version):
    if not workspace.exists():
        git(repo, "worktree", "add", "--quiet", "--detach", workspace, base)
        merge = git(workspace, "merge", "--no-commit", "--no-ff", source, check=False)
        if merge.returncode not in (0, 1):
            raise SyncError(merge.stdout)
        # Keep the fork's workflows when importing upstream app source.
        git(workspace, "restore", "--staged", "--worktree", "--source=HEAD", "--", ".github/workflows")
        conflicts = git(workspace, "diff", "--diff-filter=U", "--name-only").stdout.splitlines()
        if BUILD_FILE.as_posix() in conflicts:
            path = workspace / BUILD_FILE
            fixed = version_only_conflicts(path.read_text(encoding="utf-8"))
            if "<<<<<<<" not in fixed:
                path.write_text(fixed, encoding="utf-8")
                git(workspace, "add", "--", BUILD_FILE)
        conflicts = git(workspace, "diff", "--diff-filter=U", "--name-only").stdout.splitlines()
        if conflicts:
            raise SyncError(f"Resolve {', '.join(conflicts)} in {workspace} and commit the merge.")
        if not ancestor(workspace, source, "HEAD"):
            build = workspace / BUILD_FILE
            build.write_text(updated_build_file(build.read_text(encoding="utf-8"), *version), encoding="utf-8")
            git(workspace, "add", "--", BUILD_FILE)
            git(workspace, "commit", "-m", f"Merge Nuvio TV {tag} for Nexus Media {channel.title()}")

    if git(workspace, "status", "--porcelain").stdout.strip():
        raise SyncError(f"Candidate needs a clean resolved commit in {workspace}.")
    if not ancestor(workspace, base, "HEAD") or not ancestor(workspace, source, "HEAD"):
        raise SyncError(f"Candidate in {workspace} needs the current Nexus branch and exact Nuvio release.")
    if git(workspace, "diff", "--name-only", base, "HEAD", "--", ".github/workflows").stdout.strip():
        raise SyncError("Candidate changes the Nexus Media GitHub workflows.")
    build = workspace / BUILD_FILE
    contents = build.read_text(encoding="utf-8")
    if parse_versions(contents) != version:
        build.write_text(updated_build_file(contents, *version), encoding="utf-8")
        git(workspace, "add", "--", BUILD_FILE)
        git(workspace, "commit", "-m", f"Set Nexus Media version {version[0]}")
    return git(workspace, "rev-parse", "HEAD").stdout.strip()


def download_baseline(repo, state, release):
    tag = release["tag_name"]
    assets = [asset for asset in release["assets"] if asset["name"] == ASSET]
    if len(assets) != 1 or not assets[0]["digest"]:
        raise SyncError(f"Could not identify the signed Nexus APK for {tag}.")
    folder = state / "baselines" / tag
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / ASSET
    if not path.is_file():
        run(GH, "release", "download", tag, "--repo", FORK,
            "--pattern", ASSET, "--dir", folder, cwd=repo)
    if digest(path) != assets[0]["digest"]:
        # A failed download can be retried on the next run.
        path.unlink()
        raise SyncError(f"The downloaded Nexus APK for {tag} failed its checksum.")
    return path


def digest(path):
    with path.open("rb") as file:
        return "sha256:" + hashlib.file_digest(file, "sha256").hexdigest()


def write_json(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def current_publications(repo):
    stable = api(repo, f"repos/{FORK}/releases/latest")
    releases = api(repo, f"repos/{FORK}/releases?per_page=100")
    if stable["draft"] or stable["prerelease"] or not version_key(stable["tag_name"]):
        raise SyncError("The Nexus Stable feed did not identify a published Stable version.")
    return stable, latest_beta(releases), releases


def finish_release(repo, pending):
    plan = json.loads(pending.read_text(encoding="utf-8"))
    tag = plan["tag"]
    channel = plan["channel"]
    apk = Path(plan["apk"])
    if not apk.is_file() or digest(apk) != plan["digest"]:
        raise SyncError(f"Verified APK for {tag} is missing or has changed.")
    stable, beta, releases = current_publications(repo)
    release = next((entry for entry in releases if entry["tag_name"] == tag), None)
    if release is None or release["draft"]:
        if stable["tag_name"] != plan["stable"] or (beta and beta["tag_name"]) != plan["beta"]:
            raise SyncError("Published versions changed during verification. Review the pending candidate.")
        ref = f"refs/heads/codex/{channel}"
        head = remote_heads(repo)[channel]
        if head not in (plan["base"], plan["commit"]):
            raise SyncError(f"Nexus {channel} changed during verification. Review the pending candidate.")
        # Push the exact tested source and tag together. The pending plan supports retries after a network failure.
        git(repo, "push", "--atomic", "origin", f'{plan["commit"]}:{ref}', f'{plan["commit"]}:refs/tags/{tag}')
        notes = pending.with_suffix(".md")
        notes.write_text(
            f'Includes [Nuvio TV {plan["upstream"]}](https://github.com/{UPSTREAM}/releases/tag/{plan["upstream"]}).\n\n'
            "Nexus Media is a fork of Nuvio TV.\n\n"
            "Automatic checks verified the Android TV upgrade before publication.\n",
            encoding="utf-8",
        )
        prerelease_flags = ["--prerelease"] if channel == "beta" else []
        latest_flag = "--latest=false" if channel == "beta" else "--latest"
        if release is None:
            run(GH, "release", "create", tag, str(apk), "--repo", FORK,
                "--draft", "--verify-tag", "--title", f"Nexus Media {tag.removeprefix('v')}",
                "--notes-file", notes, "--latest=false", *prerelease_flags, cwd=repo)
        elif not release["assets"]:
            run(GH, "release", "upload", tag, str(apk), "--repo", FORK, cwd=repo)

        _, _, releases = current_publications(repo)
        release = next((entry for entry in releases if entry["tag_name"] == tag), None)
        verify_publication(release, plan, draft=True)
        run(GH, "release", "edit", tag, "--repo", FORK, "--draft=false", latest_flag, *prerelease_flags, cwd=repo)

    stable, beta, releases = current_publications(repo)
    verified = next((entry for entry in releases if entry["tag_name"] == tag), None)
    verify_publication(verified, plan, draft=False)
    if stable["tag_name"] != (plan["stable"] if channel == "beta" else tag):
        raise SyncError("GitHub's latest Stable release is unexpected after publication.")
    pending.unlink()
    logging.info("Published Nexus %s %s from Nuvio %s.", channel, tag, plan["upstream"])


def verify_publication(release, plan, *, draft):
    if (not release or release["draft"] != draft
            or release["prerelease"] != (plan["channel"] == "beta")
            or len(release["assets"]) != 1
            or release["assets"][0]["name"] != ASSET
            or release["assets"][0]["digest"] != plan["digest"]):
        raise SyncError("GitHub release metadata or APK digest differs from the verified candidate.")


def sync_channel(repo, state, channel, upstream, check):
    pending = state / f"pending-{channel}.json"
    if upstream is None:
        return "No prerelease newer than upstream Stable."

    tag = upstream["tag_name"]
    source = fetch_tag(repo, "upstream", tag)
    heads = remote_heads(repo)
    stable, beta, _ = current_publications(repo)
    published = {
        release["tag_name"]: fetch_tag(repo, "published", release["tag_name"])
        for release in (stable, beta) if release
    }
    current = stable if channel == "stable" else beta
    if current and ancestor(repo, source, published[current["tag_name"]]):
        return f"Nuvio {tag} is already in Nexus {current['tag_name']}."

    base = heads[channel]
    if check:
        merge = git(repo, "merge-tree", "--write-tree", "--name-only", base, source, check=False)
        if merge.returncode not in (0, 1):
            raise SyncError(merge.stdout)
        if merge.returncode:
            conflicts = ", ".join(line for line in merge.stdout.splitlines()[1:] if "/" in line and " " not in line)
            return f"Nuvio {tag} needs merge preparation ({conflicts})."
        return f"Nuvio {tag} can be built for Nexus {channel}."

    version = next_version(repo, heads, published.values(), channel)
    workspace = state / f"preview-{channel}-{tag}"
    commit = prepare_candidate(repo, workspace, base, source, channel, tag, version)
    stable_apk = download_baseline(repo, state, stable)
    beta_apk = download_baseline(repo, state, beta) if beta else None
    output = state / "candidates" / version[0]
    output.mkdir(parents=True, exist_ok=True)
    log_path = output / "verification.log"
    logging.info("Verifying Nexus %s from Nuvio %s in %s.", version[0], tag, workspace)
    try:
        with log_path.open("w", encoding="utf-8") as log:
            run(POWERSHELL, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "RemoteSigned",
                "-File", SCRIPTS / "verify-nexus-release.ps1",
                "-Repo", repo, "-Source", workspace, "-Output", output,
                "-VersionName", version[0], "-VersionCode", version[1],
                "-StableApk", stable_apk, *(["-BetaApk", beta_apk] if beta_apk else []),
                cwd=repo, stdout=log)
    except (SyncError, subprocess.SubprocessError) as error:
        raise SyncError(f"Release verification failed. See {log_path}.") from error
    apk = output / ASSET
    write_json(pending, {"channel": channel, "tag": f"v{version[0]}", "upstream": tag,
                         "base": base, "commit": commit, "apk": str(apk), "digest": digest(apk),
                         "stable": stable["tag_name"], "beta": beta and beta["tag_name"]})
    finish_release(repo, pending)
    return f"Published Nexus v{version[0]} from Nuvio {tag}."


def main():
    import msvcrt

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=SCRIPTS.parent)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="Check both feeds without building or publishing.")
    mode.add_argument("--publish", action="store_true", help="Build, verify and publish new releases.")
    args = parser.parse_args()
    repo = args.repo.resolve()
    state = repo / ".gradle/nexus-upstream"
    state.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(filename=state / "sync.log", format="%(asctime)s %(levelname)s %(message)s",
                        level=logging.INFO, encoding="utf-8")
    os.environ["GIT_TERMINAL_PROMPT"] = "0"
    os.environ["PATH"] = str(GH.parent) + os.pathsep + os.environ["PATH"]
    if git(repo, "remote", "get-url", "origin").stdout.strip() != f"https://github.com/{FORK}.git":
        raise SyncError("The configured origin is not the Nexus Media release repository.")

    with (state / "sync.lock").open("a+b") as lock:
        lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            logging.info("A release check is already running.")
            return 0
        status = {"checked_at": datetime.now(timezone.utc).isoformat(), "mode": "check" if args.check else "publish"}
        failed = False
        try:
            if args.publish:
                for channel in ("stable", "beta"):
                    pending = state / f"pending-{channel}.json"
                    if pending.exists():
                        finish_release(repo, pending)
            feed = read_feed(repo)
            for channel, release in feed.items():
                try:
                    status[channel] = sync_channel(repo, state, channel, release, args.check)
                    logging.info("%s: %s", channel.title(), status[channel])
                except (SyncError, OSError, subprocess.SubprocessError, SystemExit) as error:
                    failed = True
                    status[channel] = str(error)
                    logging.error("%s: %s", channel.title(), error)
        except (SyncError, OSError, subprocess.SubprocessError, ValueError) as error:
            failed = True
            status["error"] = str(error)
            logging.error("Release check: %s", error)
        finally:
            write_json(state / "last-run.json", status)
        if sys.stdout:
            print(json.dumps(status, indent=2))
        return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
