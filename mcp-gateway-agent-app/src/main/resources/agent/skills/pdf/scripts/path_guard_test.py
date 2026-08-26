# flake8: noqa
# yapf: disable
"""
路径守卫测试（整改工单 0018）

与 check_bounding_boxes_test.py 同例：unittest + 文档/手工运行（不在 CI 自动执行）。
覆盖：
  - 正常用例：工作区内相对路径放行，行为不变；合法字段 JSON 脚本成功执行
  - 攻击用例：`../` 逃逸 / 绝对路径被拒绝，非零退出且报错可读（4 个脚本的读与写边界）
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPTS_DIR)

from path_guard import guard_path  # noqa: E402

PYTHON = sys.executable
GUARD_ESCAPE_MARK = '逃逸出工作区'
GUARD_ABSOLUTE_MARK = '拒绝绝对路径'


class TestPathGuardUnit(unittest.TestCase):
    """共享守卫逻辑单元测试"""

    def setUp(self):
        self._old_cwd = os.getcwd()
        self._workdir = tempfile.mkdtemp(prefix='path_guard_ws_')
        os.chdir(self._workdir)

    def tearDown(self):
        os.chdir(self._old_cwd)

    def test_relative_path_inside_workspace_allowed(self):
        resolved = guard_path('doc.pdf', '输入 PDF')
        self.assertEqual(resolved, os.path.join(self._workdir, 'doc.pdf'))

    def test_relative_subpath_inside_workspace_allowed(self):
        resolved = guard_path(os.path.join('sub', 'doc.pdf'), '输入 PDF')
        self.assertEqual(resolved, os.path.join(self._workdir, 'sub', 'doc.pdf'))

    def test_dotdot_escape_rejected(self):
        with self.assertRaises(SystemExit) as ctx:
            guard_path(os.path.join('..', 'evil.pdf'), '输入 PDF')
        self.assertEqual(ctx.exception.code, 1)

    def test_multi_dotdot_escape_rejected(self):
        with self.assertRaises(SystemExit) as ctx:
            guard_path(os.path.join('..', '..', 'etc', 'passwd'), '输入 PDF')
        self.assertEqual(ctx.exception.code, 1)

    def test_absolute_path_rejected(self):
        absolute = os.path.join(self._workdir, '..', 'outside.pdf')
        with self.assertRaises(SystemExit) as ctx:
            guard_path(absolute, '输入 PDF')
        self.assertEqual(ctx.exception.code, 1)

    def test_absolute_path_inside_workspace_rejected(self):
        absolute = os.path.join(self._workdir, 'inside.pdf')
        with self.assertRaises(SystemExit) as ctx:
            guard_path(absolute, '输入 PDF')
        self.assertEqual(ctx.exception.code, 1)

    def test_empty_path_rejected(self):
        with self.assertRaises(SystemExit) as ctx:
            guard_path('', '输入 PDF')
        self.assertEqual(ctx.exception.code, 1)


def _run_script(script, args, cwd):
    """以给定 cwd 运行脚本，返回 (returncode, combined_output)。"""
    proc = subprocess.run(
        [PYTHON, os.path.join(SCRIPTS_DIR, script)] + args,
        cwd=cwd,
        capture_output=True,
        text=True,
        timeout=60,
    )
    return proc.returncode, proc.stdout + proc.stderr


class TestPdfScriptsPathGuard(unittest.TestCase):
    """4 个 PDF 脚本的读/写边界攻击与正常用例（脚本级 subprocess）"""

    SAMPLE_FIELDS = {
        'form_fields': [{
            'description': 'Name',
            'page_number': 1,
            'label_bounding_box': [10, 10, 50, 30],
            'entry_bounding_box': [60, 10, 150, 30],
        }]
    }

    def setUp(self):
        self._old_cwd = os.getcwd()
        self._workdir = tempfile.mkdtemp(prefix='pdf_guard_ws_')
        os.chdir(self._workdir)
        with open('fields.json', 'w') as f:
            json.dump(self.SAMPLE_FIELDS, f)

    def tearDown(self):
        os.chdir(self._old_cwd)

    # ---- 正常用例：工作区内相对路径行为不变 ----

    def test_check_bounding_boxes_normal_path_succeeds(self):
        code, out = _run_script('check_bounding_boxes.py', ['fields.json'],
                                self._workdir)
        self.assertEqual(code, 0, out)
        self.assertIn('SUCCESS', out)

    def test_fill_scripts_normal_path_not_blocked_by_guard(self):
        """守卫放行工作区内正常路径；后续错误源于文件不存在而非守卫拒绝。"""
        for script, args in [
            ('extract_form_field_info.py', ['in.pdf', 'out.json']),
            ('fill_fillable_fields.py', ['in.pdf', 'fields.json', 'out.pdf']),
            ('fill_pdf_form_with_annotations.py',
             ['in.pdf', 'fields.json', 'out.pdf']),
        ]:
            with self.subTest(script=script):
                code, out = _run_script(script, args, self._workdir)
                self.assertNotEqual(code, 0, f'{script} 应因输入缺失而失败')
                self.assertNotIn(GUARD_ESCAPE_MARK, out)
                self.assertNotIn(GUARD_ABSOLUTE_MARK, out)

    # ---- 攻击用例：../ 逃逸（读边界与写边界） ----

    def test_extract_guard_rejects_read_and_write_escape(self):
        cases = [
            # 写入边界逃逸（输出 JSON 越界）
            ['in.pdf', os.path.join('..', 'evil.json')],
            # 读边界逃逸 + 正常写入（输入 PDF 越界）
            [os.path.join('..', 'evil.pdf'), 'out.json'],
            [os.path.join('..', '..', 'evil.pdf'), 'out.json'],
        ]
        for args in cases:
            with self.subTest(args=args):
                code, out = _run_script('extract_form_field_info.py', args,
                                        self._workdir)
                self.assertEqual(code, 1, out)
                self.assertIn(GUARD_ESCAPE_MARK, out)

    def test_fill_fillable_guard_rejects_escape_on_all_boundaries(self):
        cases = [
            [os.path.join('..', 'evil.pdf'), 'fields.json', 'out.pdf'],
            ['in.pdf', os.path.join('..', 'evil.json'), 'out.pdf'],
            ['in.pdf', 'fields.json', os.path.join('..', 'evil.pdf')],
        ]
        for args in cases:
            with self.subTest(args=args):
                code, out = _run_script('fill_fillable_fields.py', args,
                                        self._workdir)
                self.assertEqual(code, 1, out)
                self.assertIn(GUARD_ESCAPE_MARK, out)

    def test_fill_annotations_guard_rejects_escape_on_all_boundaries(self):
        cases = [
            [os.path.join('..', 'evil.pdf'), 'fields.json', 'out.pdf'],
            ['in.pdf', os.path.join('..', 'evil.json'), 'out.pdf'],
            ['in.pdf', 'fields.json', os.path.join('..', 'evil.pdf')],
        ]
        for args in cases:
            with self.subTest(args=args):
                code, out = _run_script(
                    'fill_pdf_form_with_annotations.py', args, self._workdir)
                self.assertEqual(code, 1, out)
                self.assertIn(GUARD_ESCAPE_MARK, out)

    def test_bounding_boxes_guard_rejects_escape(self):
        code, out = _run_script('check_bounding_boxes.py',
                                [os.path.join('..', 'evil.json')],
                                self._workdir)
        self.assertEqual(code, 1, out)
        self.assertIn(GUARD_ESCAPE_MARK, out)

    # ---- 攻击用例：绝对路径 ----

    def test_all_scripts_reject_absolute_paths(self):
        absolute = os.path.join(self._workdir, '..', 'outside')
        cases = [
            ('extract_form_field_info.py', [absolute, 'out.json']),
            ('extract_form_field_info.py', ['in.pdf', absolute + '.json']),
            ('fill_fillable_fields.py', [absolute, 'fields.json', 'out.pdf']),
            ('fill_fillable_fields.py', ['in.pdf', absolute + '.json',
                                         'out.pdf']),
            ('fill_fillable_fields.py', ['in.pdf', 'fields.json',
                                         absolute + '.pdf']),
            ('fill_pdf_form_with_annotations.py',
             [absolute, 'fields.json', 'out.pdf']),
            ('fill_pdf_form_with_annotations.py',
             ['in.pdf', absolute + '.json', 'out.pdf']),
            ('fill_pdf_form_with_annotations.py',
             ['in.pdf', 'fields.json', absolute + '.pdf']),
            ('check_bounding_boxes.py', [absolute + '.json']),
        ]
        for script, args in cases:
            with self.subTest(script=script, args=args):
                code, out = _run_script(script, args, self._workdir)
                self.assertEqual(code, 1, out)
                self.assertIn(GUARD_ABSOLUTE_MARK, out)


if __name__ == '__main__':
    unittest.main()