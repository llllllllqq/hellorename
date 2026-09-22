#!/usr/bin/env python3
"""
从 CHANGELOG.md 里抽出某个版本的说明，用于生成 Release notes。

用法:
    python3 tools/changelog_notes.py v1.0.1 CHANGELOG.md > notes.md
"""

from __future__ import annotations

import re
import sys


def extract(version: str, changelog_path: str) -> str:
    version = version.lstrip("v")
    try:
        with open(changelog_path, encoding="utf-8") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return ""

    collecting = False
    out: list[str] = []
    for line in lines:
        if line.startswith("## "):
            if collecting:
                break
            collecting = bool(re.match(rf"##\s*\[?v?{re.escape(version)}\]?", line))
            continue
        if collecting:
            out.append(line)
    return "\n".join(out).strip()


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    text = extract(sys.argv[1], sys.argv[2])
    if not text:
        print("见 CHANGELOG.md")
        return 0
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
