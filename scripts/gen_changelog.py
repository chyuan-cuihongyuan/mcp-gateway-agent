#!/usr/bin/env python3
"""CHANGELOG 生成器（移植自主仓 SELFLOOP D04 / loop-92；AUTOLOOP al-13 / 工单 1013 推广）。

从 git log 提取 conventional commits（feat/fix/docs/chore/test/refactor/security），
按 type 分组、按 loop-NN / al-NN 归属聚合，生成/前置更新 CHANGELOG.md。
零第三方依赖；cwd 的 git 仓库即可运行。

用法：
  python3 scripts/gen_changelog.py [--since <rev>] [--out CHANGELOG.md]
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

TYPE_ORDER = ["security", "feat", "fix", "refactor", "perf", "test", "docs", "chore", "build"]
TYPE_LABEL = {
    "security": "🔒 安全",
    "feat": "✨ 新增",
    "fix": "🐛 修复",
    "refactor": "♻️ 重构",
    "perf": "⚡ 性能",
    "test": "✅ 测试",
    "docs": "📝 文档",
    "chore": "🔧 杂务",
    "build": "📦 构建",
}
COMMIT_RE = re.compile(r"^(feat|fix|docs|chore|test|refactor|perf|build|security)"
                       r"(\([^)]*\))?!?:\s*(.+)$", re.M)
LOOP_RE = re.compile(r"\b(loop|al)-(\d+)\b")


def run_git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def collect_commits(since: str | None) -> list[tuple[str, str, str]]:
    """返回 [(hash, subject, date)]，新→旧。"""
    fmt = "%h%x09%ad%x09%s"
    cmd = ["log", f"--format={fmt}", "--date=short"]
    if since:
        cmd.append(f"{since}..HEAD")
    out = run_git(*cmd)
    commits = []
    for line in out.splitlines():
        parts = line.split("\t", 2)
        if len(parts) == 3:
            commits.append((parts[0], parts[2], parts[1]))
    return commits


def group_by_loop(commits):
    loops: dict[str, list] = defaultdict(list)
    unassigned: list = []
    for h, subj, date in commits:
        m = COMMIT_RE.match(subj)
        if not m:
            continue
        typ, _, desc = m.group(1), m.group(2), m.group(3).strip()
        lm = LOOP_RE.search(subj)
        key = f"{lm.group(1)}-{lm.group(2)}" if lm else "pre-loop"
        (loops[key] if lm else unassigned).append((typ, desc, h, date))
    return loops, unassigned


def render(loops, unassigned, repo_name: str) -> str:
    lines = [f"# CHANGELOG — {repo_name}", "",
             "> 由 `scripts/gen_changelog.py` 从 conventional commits 生成"
             "（移植自主仓 loop-92，AUTOLOOP al-13 推广）；手动修改会被下次生成覆盖。", ""]
    if unassigned:
        lines.append("## 未归属循环的历史提交")
        lines.append("")
        by_type = defaultdict(list)
        for typ, desc, h, date in unassigned:
            by_type[typ].append(f"- {desc}（{h}，{date}）")
        for typ in TYPE_ORDER:
            if typ in by_type:
                lines.append(f"### {TYPE_LABEL[typ]}")
                lines.extend(sorted(by_type[typ]))
                lines.append("")
    for loop in sorted(loops, key=lambda x: int(x.split("-")[1]), reverse=True):
        entries = loops[loop]
        dates = sorted({e[3] for e in entries})
        lines.append(f"## {loop}（{dates[0]}~{dates[-1]}，{len(entries)} 项）")
        lines.append("")
        by_type = defaultdict(list)
        for typ, desc, h, _ in entries:
            by_type[typ].append(f"- {desc}（{h}）")
        for typ in TYPE_ORDER:
            if typ in by_type:
                lines.append(f"### {TYPE_LABEL[typ]}")
                lines.extend(by_type[typ])
                lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default=None, help="起始 rev（不含），默认全历史")
    ap.add_argument("--out", default="CHANGELOG.md")
    args = ap.parse_args()

    repo_name = run_git("config", "--get", "remote.origin.url").strip().rsplit("/", 1)[-1].removesuffix(".git") \
        or "repo"
    commits = collect_commits(args.since)
    loops, unassigned = group_by_loop(commits)
    if not loops and not unassigned:
        print("无可归类的 conventional commits，跳过生成")
        return 0
    content = render(loops, unassigned, repo_name)
    out = Path(args.out)
    out.write_text(content, encoding="utf-8")
    print(f"生成 {out}：{sum(len(v) for v in loops.values()) + len(unassigned)} 项提交，"
          f"{len(loops)} 个循环段")
    return 0


if __name__ == "__main__":
    sys.exit(main())
