"""Read-only acceptance checks for a pulled copy of the owned discard fixture.

No device commands, remote access, restoration or deletion are performed here.
The baseline is the prepared host fixture, not an arbitrary user repository.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


HEAD = "353bd21bb14a19dac1441e3b0ef04c45f74e4ffe"
REMOTE = "https://github.com/CodyBontecou/gitsync-android-push-check-20261102.git"
NEW_PATHS = [f"folder-{letter}/notes.md" for letter in "abcdefg"] + [
    "long-reviewed-folder-name/another-reviewed-folder-name/notes_日本語.md"
]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(
        ["git", "-C", str(root), *args],
        env={**os.environ, "GIT_OPTIONAL_LOCKS": "0"}, timeout=30,
    )


def files(root):
    result = {}
    for directory, folders, names in os.walk(root, followlinks=False):
        if Path(directory) == root:
            folders[:] = [name for name in folders if name != ".git"]
        for name in folders + names:
            path = Path(directory) / name
            require(not path.is_symlink(), "Unexpected symbolic link in generated fixture")
        for name in names:
            path = Path(directory) / name
            require(path.is_file(), "Unexpected special file in generated fixture")
            result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def index(root):
    return git(root, "ls-files", "--stage", "-z").split(b"\0")[:-1]


def verify(baseline, readback, phase):
    baseline, readback = Path(baseline).resolve(), Path(readback).resolve()
    for root in (baseline, readback):
        require((root / ".git").is_dir() and not (root / ".git").is_symlink(), "Expected a standalone fixture checkout")
        require(git(root, "rev-parse", "HEAD").decode().strip() == HEAD, "Fixture HEAD changed")
        require(git(root, "symbolic-ref", "HEAD").strip() == b"refs/heads/main", "Fixture branch changed")
        require(git(root, "remote", "get-url", "origin").decode().strip() == REMOTE, "Fixture remote changed")
        require(not (root / ".git/index.lock").exists(), "An index lock remains")
        require(not list((root / ".git").glob("gitsync-discard-*.index*")), "A private discard index remains")

    expected = files(baseline)
    prepared = {"README.md": b"Reviewed README draft.\n", "note.md": b"Later note draft.\n",
                "ignored/keep.txt": b"Keep this ignored fixture file.\n"}
    prepared.update({path: f"Generated untracked fixture {number}.\n".encode()
                     for number, path in enumerate(NEW_PATHS, 1)})
    require(expected == {path: hashlib.sha256(data).hexdigest() for path, data in prepared.items()}, "Baseline working files are not the prepared generated fixture")
    expected_index = index(baseline)
    staged_note = git(baseline, "show", ":note.md")
    require(staged_note == b"Staged note draft.\n", "Baseline staged note changed")
    head_index = []
    for entry in git(baseline, "ls-tree", "-rz", "HEAD").split(b"\0")[:-1]:
        metadata, path = entry.split(b"\t", 1)
        mode, kind, oid = metadata.split(b" ")
        require(kind == b"blob" and mode == b"100644", "Unexpected HEAD entry in generated fixture")
        head_index.append(mode + b" " + oid + b" 0\t" + path)
        if phase == "all" or (phase == "single" and path == b"note.md"):
            expected[path.decode()] = hashlib.sha256(git(baseline, "cat-file", "blob", oid.decode())).hexdigest()
    if phase == "all":
        for path in NEW_PATHS:
            del expected[path]
        expected_index = head_index
    elif phase == "single":
        expected_index = [entry for entry in expected_index if entry.split(b"\t", 1)[1] != b"note.md"]
        expected_index += [entry for entry in head_index if entry.split(b"\t", 1)[1] == b"note.md"]

    require(files(readback) == expected, "Working file paths or exact bytes differ from the expected phase")
    require(sorted(index(readback)) == sorted(expected_index), "Staged paths, modes or blob identities differ")
    for name in ("config", "HEAD", "refs/heads/main", "info/exclude"):
        require((baseline / ".git" / name).read_bytes() == (readback / ".git" / name).read_bytes(), f"Fixture metadata changed: {name}")
    if phase == "cancel":
        require((baseline / ".git/index").read_bytes() == (readback / ".git/index").read_bytes(), "Cancel changed the exact index bytes")
    return {"phase": phase, "head": HEAD, "working_files": len(expected),
            "index_entries": len(expected_index), "passed": True,
            "device_ui_verified": False, "device_cleanup_verified": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--readback", required=True, type=Path)
    parser.add_argument("--phase", required=True, choices=("cancel", "single", "all"))
    args = parser.parse_args()
    print(json.dumps(verify(args.baseline, args.readback, args.phase), indent=2))
