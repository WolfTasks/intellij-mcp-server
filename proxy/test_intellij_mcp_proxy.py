import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from intellij_mcp_proxy import (  # noqa: E402
    Instance,
    default_state_dir,
    is_pid_alive,
    is_within,
    load_instances,
    normalize_path,
    resolve_project_dir,
    select_instance,
)


def inst(port, name, path, pid=1, mtime=0.0):
    return Instance(port=port, project_name=name, project_path=path, pid=pid, mtime=mtime)


def record(port, path="C:/p/X", pid=1, name="X"):
    return json.dumps({"port": port, "url": f"http://127.0.0.1:{port}/mcp",
                       "projectName": name, "projectPath": path, "pid": pid})


class ResolutionTest(unittest.TestCase):
    def test_windows_normalization(self):
        self.assertEqual(normalize_path(r"C:\Users\Admin\Proj\\", windows=True),
                         normalize_path("c:/users/admin/proj", windows=True))

    def test_root_keeps_trailing_slash(self):
        self.assertEqual(normalize_path("C:\\", windows=True), "c:/")
        self.assertEqual(normalize_path("/", windows=False), "/")

    def test_posix_normalization_keeps_case(self):
        self.assertEqual(normalize_path("/home/a/Proj/", windows=False), "/home/a/Proj")

    def test_prefix_trap(self):
        self.assertFalse(is_within("C:/x/FooBar", "C:/x/Foo", windows=True))
        self.assertTrue(is_within("C:/x/Foo/src", r"c:\x\foo", windows=True))
        self.assertTrue(is_within("C:/x/Foo", "C:/x/Foo", windows=True))

    def test_posix_is_case_sensitive(self):
        self.assertFalse(is_within("/home/a/Proj", "/home/a/proj", windows=False))
        self.assertTrue(is_within("/home/a/Proj/s", "/home/a/Proj", windows=False))

    def test_nearest_parent_wins(self):
        a = inst(6789, "FileSearch", "C:/p/FileSearch")
        b = inst(6790, "SearchAndGroup", "C:/p/FileSearch/SearchAndGroup")
        self.assertEqual(select_instance([a, b], "C:/p/FileSearch/SearchAndGroup/sub",
                                         alive=lambda p: True, windows=True), b)

    def test_newest_mtime_breaks_tie(self):
        a = inst(6789, "X", "C:/p/X", mtime=1.0)
        b = inst(6790, "X", "C:/p/X", mtime=2.0)
        self.assertEqual(select_instance([a, b], "C:/p/X", alive=lambda p: True, windows=True), b)

    def test_dead_pid_and_excluded_port_are_skipped(self):
        a = inst(6789, "X", "C:/p/X", pid=1)
        b = inst(6791, "X", "C:/p/X", pid=2, mtime=1.0)
        self.assertEqual(select_instance([a, b], "C:/p/X", alive=lambda p: p == 2, windows=True), b)
        self.assertIsNone(select_instance([b], "C:/p/X", alive=lambda p: True,
                                          exclude_ports=frozenset({6791}), windows=True))

    def test_no_match_returns_none(self):
        a = inst(6789, "Other", "C:/p/Other")
        self.assertIsNone(select_instance([a], "C:/p/X", alive=lambda p: True, windows=True))

    def test_load_skips_garbage(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            (d / "6789.json").write_text(record(6789), encoding="utf-8")
            (d / "6788.json").write_text("not json", encoding="utf-8")
            (d / "7000.json").write_text(record(7000), encoding="utf-8")
            (d / "6790.json").write_text(
                '{"port":6790,"projectName":"X","projectPath":null,"pid":1}', encoding="utf-8")
            (d / "6792.json").write_text(
                '{"port":"6792","projectName":"X","projectPath":"C:/p","pid":1}', encoding="utf-8")
            (d / "6793.json").write_text(
                '{"port":6793,"projectName":"X","projectPath":"C:/p"}', encoding="utf-8")
            (d / "6791.json.123.tmp").write_text(record(6791), encoding="utf-8")
            got = load_instances(d)
            self.assertEqual([i.port for i in got], [6789])
            self.assertEqual(got[0].project_path, "C:/p/X")
            self.assertGreater(got[0].mtime, 0)

    def test_load_missing_dir_is_empty(self):
        with tempfile.TemporaryDirectory() as t:
            self.assertEqual(load_instances(Path(t) / "nope"), [])

    def test_is_pid_alive_does_not_kill(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            self.assertTrue(is_pid_alive(child.pid))
            self.assertTrue(is_pid_alive(child.pid))
            self.assertIsNone(child.poll())
        finally:
            child.kill()
            child.wait()
        self.assertFalse(is_pid_alive(child.pid))

    def test_is_pid_alive_invalid(self):
        self.assertFalse(is_pid_alive(0))
        self.assertFalse(is_pid_alive(-5))

    def test_project_dir_precedence(self):
        env = {"INTELLIJ_MCP_PROJECT_DIR": "A", "CLAUDE_PROJECT_DIR": "B"}
        self.assertEqual(resolve_project_dir("CLI", env, "CWD"), "CLI")
        self.assertEqual(resolve_project_dir(None, env, "CWD"), "A")
        self.assertEqual(resolve_project_dir(None, {"CLAUDE_PROJECT_DIR": "B"}, "CWD"), "B")
        self.assertEqual(resolve_project_dir(None, {}, "CWD"), "CWD")

    def test_default_state_dir(self):
        self.assertEqual(default_state_dir({"INTELLIJ_MCP_SERVER_HOME": "/tmp/h"}), Path("/tmp/h"))
        self.assertEqual(default_state_dir({}), Path.home() / ".intellij-mcp-server")


if __name__ == "__main__":
    unittest.main()
