#!/usr/bin/env python3
"""从 CHANGELOG.md 抽取指定版本的段落，作为 GitHub Release 的正文。

用法：release_notes.py <changelog 路径> <版本号> [--require]

- 版本号可带或不带前导 `v`（`v0.12.0` 与 `0.12.0` 等价）。
- 段落 = `## [X.Y.Z] ...` 标题之后、下一个 `## ` 标题之前的内容。
- `--require`：抽不到内容时以退出码 2 失败（release 流程用它阻断「正文为空」的发布）。
- 抽不到内容时打印空行并以 0 退出（未加 `--require` 时）。
"""

from __future__ import annotations

import re
import sys

EXIT_OK = 0
EXIT_MISSING = 2


def normalize_version(raw: str) -> str:
    """去掉前导 `v`（tag 是 `vX.Y.Z`，CHANGELOG 段落是 `X.Y.Z`）。"""
    return raw[1:] if raw.startswith("v") else raw


def extract_release_notes(changelog_text: str, version: str) -> str:
    """抽取 version 段落正文；无该段落时返回空串。"""
    version = normalize_version(version)
    # 段落标题：`## [1.2.3] - 2026-01-01` / `## 1.2.3` / `## [1.2.3]` 皆可
    heading = re.compile(
        r"^##\s+\[?" + re.escape(version) + r"\]?(?:\s|$).*$",
        re.MULTILINE,
    )
    match = heading.search(changelog_text)
    if not match:
        return ""
    body_start = match.end()
    next_heading = re.compile(r"^##\s+", re.MULTILINE).search(changelog_text, body_start)
    body_end = next_heading.start() if next_heading else len(changelog_text)
    return changelog_text[body_start:body_end].strip()


def main(argv: list[str]) -> int:
    args = [a for a in argv[1:] if not a.startswith("--")]
    require = "--require" in argv
    if len(args) != 2:
        print("用法：release_notes.py <changelog 路径> <版本号> [--require]", file=sys.stderr)
        return 1

    changelog_path, version = args
    with open(changelog_path, encoding="utf-8") as fh:
        text = fh.read()

    notes = extract_release_notes(text, version)
    if not notes:
        if require:
            print(
                f"CHANGELOG.md 中找不到版本 {normalize_version(version)} 的段落，无法生成 Release 正文。",
                file=sys.stderr,
            )
            return EXIT_MISSING
        return EXIT_OK

    print(notes)
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv))
