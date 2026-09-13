#!/usr/bin/env python3
"""workflow 最小权限审计（子仓版；移植自主仓 al-36/al-49）。

扫描本仓 .github/workflows/*.yml：
  1. 必须显式声明 permissions
  2. 声明不得超出白名单
用法：python3 tools/audit_workflow_permissions.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path.cwd()
ALLOWED_MAX = {"contents", "pull-requests", "issues", "security-events"}


def audit_file(path: Path) -> list[str]:
    problems: list[str] = []
    text = path.read_text(encoding="utf-8", errors="replace")
    if "permissions:" not in text:
        problems.append(f"{path}: 缺 permissions 显式声明")
        return problems
    for m in re.finditer(r"permissions:\s*(?:\n\s+([a-z-]+:\s*\w+))+", text):
        for line in m.group(0).splitlines():
            kv = re.match(r"\s*([a-z-]+):\s*(\w+)$", line)
            if not kv:
                continue
            scope, mode = kv.groups()
            if mode not in ("read", "none", "read-all"):
                if scope not in ALLOWED_MAX or mode in ("write-all", "write"):
                    if not (scope in ALLOWED_MAX and mode == "write"):
                        problems.append(f"{path}: {scope}: {mode} 超出最小权限白名单")
    return problems


def main() -> int:
    workflows = sorted((REPO / ".github" / "workflows").glob("*.yml"))
    if not workflows:
        print("无 workflow 可审")
        return 0
    failures: list[str] = []
    for wf in workflows:
        failures += audit_file(wf)
        print(f"ok: {wf.name}")
    if failures:
        for f in failures:
            print(f"FAIL: {f}")
        return 1
    print(f"\n{len(workflows)} 个 workflow 权限审计全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
