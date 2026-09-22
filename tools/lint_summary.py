#!/usr/bin/env python3
"""
把 Android Lint 的 XML 报告转成人类可读的摘要，并作为质量门禁：
只要出现 Error/Fatal 级别的问题就返回非 0。

用法:
    gradle lintRelease
    python3 tools/lint_summary.py app/build/reports/lint-results-release.xml \
        [--summary-file "$GITHUB_STEP_SUMMARY"]
"""

from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET

BLOCKING = {"Fatal", "Error"}
ICON = {"Fatal": "❌", "Error": "❌", "Warning": "⚠️", "Information": "ℹ️", "Ignore": "·"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report")
    parser.add_argument("--summary-file", default=None)
    args = parser.parse_args()

    if not os.path.isfile(args.report):
        print(f"没有 lint 报告: {args.report}（lint 可能没跑成功）")
        return 1

    root = ET.parse(args.report).getroot()
    issues = []
    for issue in root.findall("issue"):
        locations = issue.findall("location")
        where = ""
        if locations:
            first = locations[0]
            where = first.get("file", "")
            if first.get("line"):
                where += f":{first.get('line')}"
        issues.append(
            {
                "id": issue.get("id", "?"),
                "severity": issue.get("severity", "Warning"),
                "message": (issue.get("message") or "").strip().replace("\n", " "),
                "where": where,
            }
        )

    counts: dict[str, int] = {}
    for issue in issues:
        counts[issue["severity"]] = counts.get(issue["severity"], 0) + 1

    order = {"Fatal": 0, "Error": 1, "Warning": 2, "Information": 3, "Ignore": 4}
    issues.sort(key=lambda i: order.get(i["severity"], 9))

    lines = ["", "=" * 72, "Android Lint 摘要", "=" * 72]
    if not issues:
        lines.append("✅ 没有任何 lint 问题")
    for issue in issues:
        lines.append(
            f"{ICON.get(issue['severity'], '·')} [{issue['severity']}] {issue['id']}: {issue['message']}"
        )
        if issue["where"]:
            lines.append(f"      {issue['where']}")
    lines.append("-" * 72)
    lines.append("合计: " + (", ".join(f"{k}={v}" for k, v in counts.items()) or "0"))
    lines.append("=" * 72)
    print("\n".join(lines))

    blocking = [i for i in issues if i["severity"] in BLOCKING]

    if args.summary_file:
        md = ["", "### Android Lint", ""]
        if not issues:
            md.append("✅ 没有任何 lint 问题")
        else:
            md += ["| 级别 | 规则 | 说明 | 位置 |", "| --- | --- | --- | --- |"]
            for issue in issues[:50]:
                md.append(
                    f"| {ICON.get(issue['severity'], '·')} {issue['severity']} | {issue['id']} | "
                    f"{issue['message'][:160]} | `{issue['where']}` |"
                )
            md.append("")
            md.append(f"**Error/Fatal: {len(blocking)}**，其余为 Warning/Information")
        try:
            with open(args.summary_file, "a", encoding="utf-8") as handle:
                handle.write("\n".join(md) + "\n")
        except OSError as exc:
            print(f"写入 summary 失败: {exc}")

    if blocking:
        print(f"\n❌ 有 {len(blocking)} 个 Error/Fatal 级别的 lint 问题，门禁不通过")
        return 1
    print("\n✅ lint 门禁通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
