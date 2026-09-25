#!/usr/bin/env python3
"""Build a bounded release manifest from explicit artifacts, never runtime folders."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import quote


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--windows", type=Path, required=True)
    parser.add_argument("--android", type=Path, required=True)
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+", args.repository):
        parser.error("repository must be owner/repository")
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version) or args.version_code < 1:
        parser.error("a stable semantic version and positive version code are required")

    def asset(path):
        if not path.is_file() or path.stat().st_size == 0:
            parser.error(f"artifact is missing or empty: {path.name}")
        with path.open("rb") as handle:
            checksum = hashlib.file_digest(handle, "sha256").hexdigest()
        return {"file": path.name, "url": f"https://github.com/{args.repository}/releases/download/v{args.version}/{quote(path.name)}",
                "sha256": checksum, "size": path.stat().st_size}

    windows = asset(args.windows)
    android = dict(asset(args.android), versionName=args.version, versionCode=args.version_code)
    manifest = {"schemaVersion": 1, "version": args.version,
                "notes": args.notes.read_text(encoding="utf-8-sig").strip(), "windows": windows, "android": android}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    checksums = "".join(f"{item['sha256']}  {item['file']}\n" for item in (windows, android))
    args.output.with_name("SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    print(f"Manifest generated for v{args.version}; includes Windows and Android SHA-256 checksums.")


if __name__ == "__main__":
    main()
