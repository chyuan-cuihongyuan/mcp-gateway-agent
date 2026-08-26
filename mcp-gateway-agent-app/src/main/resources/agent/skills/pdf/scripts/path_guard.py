# flake8: noqa
# yapf: disable
"""
路径守卫（本地安全补丁，见整改工单 0018）

约束本技能脚本的全部文件读写入参：路径必须位于允许的工作区根目录内。

规则：
  1. 空入参 → 拒绝（非零退出 + 可读错误）
  2. 绝对路径（POSIX 或 Windows 盘符） → 拒绝
  3. 相对路径经 normpath 规范化后，若穿过工作区根目录（`..` 逃逸）→ 拒绝
  4. 合法入参返回工作区内的规范化绝对路径，供调用方继续使用

正常工作区内的相对路径（含子目录）行为完全不变；`../` 逃逸与绝对路径
一律以非零退出码报清晰错误，避免 Agent 误操作越界读写文件。
"""
import os
import sys


def guard_path(path, purpose='文件'):
    """校验路径必须落在允许的工作区根目录内，否则非零退出；返回规范化绝对路径。"""
    if not path:
        print(f"错误: {purpose}路径为空，拒绝执行")
        sys.exit(1)

    # 绝对路径（POSIX / 或 Windows 盘符）一律拒绝：脚本只接受工作区相对路径
    if os.path.isabs(path) or os.path.splitdrive(path)[0]:
        print(f"错误: {purpose}路径必须是工作区内的相对路径，拒绝绝对路径: {path}")
        sys.exit(1)

    workspace_root = os.path.abspath(os.getcwd())
    normalized = os.path.normpath(path)
    resolved = os.path.abspath(os.path.join(workspace_root, normalized))

    # 规范化后必须仍位于工作区根目录内，否则视为 `..` 逃逸
    # （normcase 统一大小写语义，兼容 Windows 盘符/大小写不敏感文件系统）
    if os.path.normcase(os.path.commonpath([workspace_root, resolved])) != os.path.normcase(workspace_root):
        print(f"错误: {purpose}路径逃逸出工作区目录（不允许 ../ 越界）: {path}")
        sys.exit(1)

    return resolved