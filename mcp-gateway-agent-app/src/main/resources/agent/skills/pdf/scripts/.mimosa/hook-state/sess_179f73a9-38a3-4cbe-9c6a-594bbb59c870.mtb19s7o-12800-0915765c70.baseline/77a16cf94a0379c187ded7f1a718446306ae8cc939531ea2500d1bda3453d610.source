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

try:
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import (ArrayObject, BooleanObject, DictionaryObject,
                               NameObject, RectangleObject, TextStringObject)
except ImportError:  # pragma: no cover - 无 pypdf 时仅跑 guard 单测
    PdfReader = PdfWriter = None

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPTS_DIR)

from path_guard import guard_path  # noqa: E402

PYTHON = sys.executable
GUARD_ESCAPE_MARK = '逃逸出工作区'
GUARD_ABSOLUTE_MARK = '拒绝绝对路径'


def _make_fillable_form_pdf(path, field_name='name', width=300, height=300):
    """用 pypdf 对象级配方生成含单个文本域（page 1）的可填写表单 PDF。"""
    writer = PdfWriter()
    page = writer.add_blank_page(width=width, height=height)
    field = DictionaryObject()
    field.update({
        NameObject('/FT'): NameObject('/Tx'),
        NameObject('/T'): TextStringObject(field_name),
        NameObject('/Type'): NameObject('/Annot'),
        NameObject('/Subtype'): NameObject('/Widget'),
        NameObject('/Rect'): RectangleObject([20, 20, 120, 40]),
        NameObject('/P'): page,
    })
    field_obj = writer._add_object(field)
    page[NameObject('/Annots')] = ArrayObject([field_obj])
    acroform = DictionaryObject()
    acroform.update({NameObject('/Fields'): ArrayObject([field_obj])})
    writer._root_object[NameObject('/AcroForm')] = acroform
    writer._root_object[NameObject('/NeedAppearances')] = BooleanObject(True)
    with open(path, 'wb') as f:
        writer.write(f)


def _make_blank_pdf(path, width=300, height=300):
    writer = PdfWriter()
    writer.add_blank_page(width=width, height=height)
    with open(path, 'wb') as f:
        writer.write(f)


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

    def test_windows_drive_path_rejected_on_all_platforms(self):
        # POSIX 下 os.path.isabs 不认 C:\...，守卫必须显式拒绝（跨平台语义一致）
        for drive_path in ('C:\\evil.pdf', 'c:/evil.pdf', 'D:\\sub\\evil.pdf'):
            with self.subTest(path=drive_path):
                with self.assertRaises(SystemExit) as ctx:
                    guard_path(drive_path, '输入 PDF')
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

    @unittest.skipUnless(PdfWriter is not None, 'pypdf 未安装')
    def test_extract_form_field_info_success_in_workspace(self):
        _make_fillable_form_pdf('form.pdf')
        code, out = _run_script('extract_form_field_info.py',
                                ['form.pdf', 'fields_out.json'], self._workdir)
        self.assertEqual(code, 0, out)
        with open(os.path.join(self._workdir, 'fields_out.json'),
                  encoding='utf-8') as f:
            data = json.load(f)
        self.assertTrue(any(fld.get('field_id') == 'name' for fld in data))

    @unittest.skipUnless(PdfWriter is not None, 'pypdf 未安装')
    def test_fill_fillable_fields_success_in_workspace(self):
        _make_fillable_form_pdf('form.pdf')
        with open('values.json', 'w', encoding='utf-8') as f:
            json.dump([{'field_id': 'name', 'page': 1, 'value': 'Alice'}], f)
        code, out = _run_script('fill_fillable_fields.py',
                                ['form.pdf', 'values.json', 'filled.pdf'],
                                self._workdir)
        self.assertEqual(code, 0, out)
        self.assertTrue(
            os.path.exists(os.path.join(self._workdir, 'filled.pdf')))

    @unittest.skipUnless(PdfWriter is not None, 'pypdf 未安装')
    def test_fill_annotations_success_in_workspace(self):
        _make_blank_pdf('blank.pdf')
        payload = {
            'form_fields': [{
                'page_number': 1,
                'entry_bounding_box': [10, 10, 60, 20],
                'entry_text': {
                    'text': 'Alice'
                },
            }],
            'pages': [{
                'page_number': 1,
                'image_width': 100,
                'image_height': 100,
            }],
        }
        with open('annot.json', 'w', encoding='utf-8') as f:
            json.dump(payload, f)
        code, out = _run_script('fill_pdf_form_with_annotations.py',
                                ['blank.pdf', 'annot.json', 'annot_out.pdf'],
                                self._workdir)
        self.assertEqual(code, 0, out)
        self.assertTrue(
            os.path.exists(os.path.join(self._workdir, 'annot_out.pdf')))

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
            # Windows 盘符绝对路径在任何平台都必须拒绝
            ('extract_form_field_info.py', ['C:\\evil.pdf', 'out.json']),
            ('fill_fillable_fields.py', ['in.pdf', 'C:/evil.json', 'out.pdf']),
            ('fill_pdf_form_with_annotations.py',
             ['in.pdf', 'fields.json', 'C:\\evil.pdf']),
        ]
        for script, args in cases:
            with self.subTest(script=script, args=args):
                code, out = _run_script(script, args, self._workdir)
                self.assertEqual(code, 1, out)
                self.assertIn(GUARD_ABSOLUTE_MARK, out)


if __name__ == '__main__':
    unittest.main()