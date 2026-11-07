import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from intellij_mcp_proxy import (  # noqa: E402
    BACKEND_ERROR,
    NO_WINDOW,
    TOKEN_ERROR,
    Forwarder,
    Instance,
    default_state_dir,
    is_pid_alive,
    is_within,
    load_instances,
    no_window_message,
    normalize_path,
    resolve_project_dir,
    select_instance,
    serve,
)

PROXY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "intellij_mcp_proxy.py")


def inst(port, name, path, pid=1, mtime=0.0):
    return Instance(port=port, project_name=name, project_path=path, pid=pid, mtime=mtime)


def record(port, path="C:/p/X", pid=1, name="X"):
    return json.dumps({"port": port, "url": f"http://127.0.0.1:{port}/mcp",
                       "projectName": name, "projectPath": path, "pid": pid})


class ResolutionTest(unittest.TestCase):
    def test_windows_normalization(self):
        self.assertEqual(normalize_path(r"C:\Users\Admin\Proj\\", windows=True),
                         normalize_path("c:/users/admin/proj", windows=True))

    def test_windows_long_path_prefixes(self):
        self.assertEqual(normalize_path(r"\\?\C:\X\Y", windows=True), "c:/x/y")
        self.assertEqual(normalize_path(r"\\?\UNC\srv\share\a", windows=True), "//srv/share/a")
        self.assertEqual(normalize_path(r"\\srv\share\a", windows=True), "//srv/share/a")

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

    def test_is_pid_alive_rejects_pid_beyond_dword(self):
        # On Windows a DWORD would silently truncate this to our own (live) pid.
        self.assertFalse(is_pid_alive(0x1_0000_0000 + os.getpid()))
        self.assertFalse(is_pid_alive(0x1_0000_0000))

    def test_project_dir_precedence(self):
        env = {"INTELLIJ_MCP_PROJECT_DIR": "A", "CLAUDE_PROJECT_DIR": "B"}
        self.assertEqual(resolve_project_dir("CLI", env, "CWD"), "CLI")
        self.assertEqual(resolve_project_dir(None, env, "CWD"), "A")
        self.assertEqual(resolve_project_dir(None, {"CLAUDE_PROJECT_DIR": "B"}, "CWD"), "B")
        self.assertEqual(resolve_project_dir(None, {}, "CWD"), "CWD")

    def test_default_state_dir(self):
        self.assertEqual(default_state_dir({"INTELLIJ_MCP_SERVER_HOME": "/tmp/h"}), Path("/tmp/h"))
        self.assertEqual(default_state_dir({}), Path.home() / ".intellij-mcp-server")


# ---------------------------------------------------------------------------
# Forwarding / stdio loop
# ---------------------------------------------------------------------------

TOKEN = "old-token-0123456789abcdefghijklmnopqrstuv"
NEW_TOKEN = "new-token-0123456789abcdefghijklmnopqrstuv"
BACKEND_TOOLS = {"tools": [{"name": "read_file", "description": "d", "inputSchema": {"type": "object"}}]}


def free_port():
    """A port on 127.0.0.1 that nobody listens on (connections are refused)."""
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class _Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        pass  # client gave up (e.g. read timeout tests); keep test output clean


class _Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        backend = self.server.backend
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        message = json.loads(body.decode("utf-8"))
        with backend.lock:
            # HTTPMessage: case-insensitive lookup (urllib sends "X-intellij-project")
            backend.requests.append((self.headers, message))
        if backend.on_request:
            backend.on_request()
        if backend.delay:
            time.sleep(backend.delay)
        auth = self.headers.get("Authorization")
        if backend.accept_tokens is not None and auth not in ["Bearer " + t for t in backend.accept_tokens]:
            status, payload = 401, {"error": "unauthorized"}
        elif backend.status != 200:
            status, payload = backend.status, {"error": "nope"}
        elif "id" not in message:
            # The real server answers notifications with an id:null error.
            status, payload = 200, {"jsonrpc": "2.0", "id": None,
                                    "error": {"code": -32601, "message": "Method not found"}}
        else:
            status, payload = 200, {"jsonrpc": "2.0", "id": message["id"], "result": backend.reply(message)}
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class FakeBackend:
    def __init__(self):
        self.lock = threading.Lock()
        self.requests = []
        self.status = 200
        self.delay = 0.0
        self.accept_tokens = None
        self.on_request = None
        self.reply = self._default_reply
        self.server = _Server(("127.0.0.1", 0), _Handler)
        self.server.backend = self
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05},
                                       daemon=True)
        self.thread.start()

    @staticmethod
    def _default_reply(message):
        method = message.get("method")
        if method == "initialize":
            return {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}},
                    "serverInfo": {"name": "intellij-mcp-server", "version": "1.2.0"}}
        if method == "tools/list":
            return BACKEND_TOOLS
        return {"content": [{"type": "text", "text": "ok"}]}

    @property
    def headers(self):
        return self.requests[-1][0]

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)


class MappedForwarder(Forwarder):
    """Maps record ports (6789..6800) to fake backend ports; unmapped ports are refused."""

    port_map: dict = {}
    dead_port: int = 0

    def _url(self, port):
        return f"http://127.0.0.1:{self.port_map.get(port, self.dead_port)}/mcp"


class ForwardingTestBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="mcp-proxy-test-")
        self.state = Path(self.tmp) / "state"
        (self.state / "instances").mkdir(parents=True)
        self.project = Path(self.tmp) / "Mein Projekt"
        self.project.mkdir()
        self.project_dir = str(self.project)
        self.token_path = self.state / "token"
        self.token_path.write_text(TOKEN + "\n", encoding="utf-8")
        self.backends = []
        self.dead_port = free_port()

    def tearDown(self):
        for b in self.backends:
            b.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def backend(self):
        b = FakeBackend()
        self.backends.append(b)
        return b

    def write_record(self, port, name="Mein Projekt", path=None, pid=1, mtime=None):
        f = self.state / "instances" / f"{port}.json"
        f.write_text(record(port, path or self.project.as_posix(), pid, name), encoding="utf-8")
        if mtime is not None:
            os.utime(f, (mtime, mtime))

    def forwarder(self, port_map, **kw):
        kw.setdefault("timeout", 5.0)
        fwd = MappedForwarder(self.state, self.project_dir, alive=lambda pid: True, **kw)
        fwd.port_map = port_map
        fwd.dead_port = self.dead_port
        return fwd


INIT = {"jsonrpc": "2.0", "id": 0, "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}}}
TOOLS_LIST = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}
TOOLS_CALL = {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "read_file", "arguments": {}}}


class ForwarderTest(ForwardingTestBase):
    def test_forwards_with_token_and_project_header(self):
        b = self.backend()
        self.write_record(6789)
        resp = self.forwarder({6789: b.port}).handle(TOOLS_LIST)
        self.assertEqual(resp["id"], 1)
        self.assertEqual(resp["result"], BACKEND_TOOLS)
        self.assertEqual(b.headers["Authorization"], "Bearer " + TOKEN)
        self.assertEqual(b.headers["X-IntelliJ-Project"], "Mein%20Projekt")
        self.assertEqual(b.headers["Content-Type"], "application/json")
        self.assertEqual(b.requests[-1][1], TOOLS_LIST)

    def test_project_header_is_percent_encoded_utf8(self):
        b = self.backend()
        self.write_record(6789, name="Größe/€")
        self.forwarder({6789: b.port}).handle(TOOLS_LIST)
        self.assertEqual(b.headers["X-IntelliJ-Project"], "Gr%C3%B6%C3%9Fe%2F%E2%82%AC")

    def test_body_is_utf8_json(self):
        b = self.backend()
        self.write_record(6789)
        msg = {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
               "params": {"name": "x", "arguments": {"text": "ä€"}}}
        self.forwarder({6789: b.port}).handle(msg)
        self.assertEqual(b.requests[-1][1], msg)

    def test_notification_produces_no_output(self):
        b = self.backend()
        self.write_record(6789)
        fwd = self.forwarder({6789: b.port})
        self.assertIsNone(fwd.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))
        self.assertEqual(b.requests[-1][1]["method"], "notifications/initialized")

    def test_notification_without_backend_produces_no_output(self):
        fwd = self.forwarder({})
        self.assertIsNone(fwd.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))

    def test_no_window(self):
        self.write_record(6790, name="Other", path=(Path(self.tmp) / "Other").as_posix())
        fwd = self.forwarder({})
        self.assertFalse(fwd.has_backend())
        init = fwd.handle(INIT)
        self.assertEqual(init["id"], 0)
        self.assertEqual(init["result"]["protocolVersion"], "2024-11-05")
        self.assertEqual(init["result"]["capabilities"]["tools"]["listChanged"], True)
        self.assertEqual(init["result"]["serverInfo"]["name"], "intellij-mcp-proxy")
        instructions = init["result"]["instructions"]
        self.assertIn("No IntelliJ window is open for", instructions)
        self.assertIn(self.project_dir, instructions)
        self.assertIn(":6790", instructions)
        self.assertEqual(fwd.handle(TOOLS_LIST)["result"], {"tools": []})
        err = fwd.handle(TOOLS_CALL)
        self.assertEqual(err["id"], 2)
        self.assertEqual(err["error"]["code"], NO_WINDOW)
        self.assertIn("No IntelliJ window is open for", err["error"]["message"])
        self.assertEqual(fwd.handle({"jsonrpc": "2.0", "id": 9, "method": "ping"})["result"], {})

    def test_no_window_message_text(self):
        msg = no_window_message("C:/p/X", [])
        self.assertEqual(
            msg,
            "No IntelliJ window is open for 'C:/p/X'. Open this project in IntelliJ IDEA "
            "(IntelliJ MCP Server plugin installed, project trusted); the proxy picks it up "
            "automatically. Open windows: none")
        msg = no_window_message("C:/p/X", [inst(6789, "A", "C:/a"), inst(6790, "B", "C:/b")])
        self.assertTrue(msg.endswith("Open windows: A (C:/a) :6789, B (C:/b) :6790"), msg)

    def test_initialize_with_backend_injects_list_changed(self):
        b = self.backend()
        self.write_record(6789)
        resp = self.forwarder({6789: b.port}).handle(INIT)
        self.assertEqual(resp["result"]["capabilities"]["tools"], {"listChanged": True})
        self.assertEqual(resp["result"]["serverInfo"]["name"], "intellij-mcp-server")
        self.assertEqual(b.requests[-1][1]["method"], "initialize")

    def test_window_opened_later_is_used_without_restart(self):
        b = self.backend()
        fwd = self.forwarder({6789: b.port})
        self.assertEqual(fwd.handle(TOOLS_LIST)["result"], {"tools": []})
        self.write_record(6789)
        self.assertTrue(fwd.has_backend())
        self.assertEqual(fwd.handle(TOOLS_LIST)["result"], BACKEND_TOOLS)

    def test_dead_pid_record_is_ignored(self):
        b = self.backend()
        self.write_record(6789, pid=1, mtime=1000)
        self.write_record(6790, pid=2, mtime=2000)
        fwd = MappedForwarder(self.state, self.project_dir, timeout=5.0, alive=lambda pid: pid == 1)
        fwd.port_map = {6789: b.port}
        fwd.dead_port = self.dead_port
        self.assertEqual(fwd.handle(TOOLS_LIST)["result"], BACKEND_TOOLS)

    def test_refused_port_falls_back_to_other_live_record(self):
        b = self.backend()
        self.write_record(6789, mtime=1000)
        self.write_record(6790, mtime=2000)  # newer, but nobody listens there
        resp = self.forwarder({6789: b.port}).handle(TOOLS_LIST)
        self.assertEqual(resp["result"], BACKEND_TOOLS)
        self.assertEqual(len(b.requests), 1)

    def test_refused_record_is_skipped_until_rewritten(self):
        b = self.backend()
        self.write_record(6790, mtime=1000)
        fwd = self.forwarder({})
        self.assertEqual(fwd.handle(TOOLS_LIST)["result"], {"tools": []})
        # The stale record is remembered: no new connect attempt, so the poller cannot flap.
        start = time.monotonic()
        self.assertFalse(fwd.has_backend())
        self.assertLess(time.monotonic() - start, 0.5)
        # The IDE rewrites the record (window reopened): it is used again.
        fwd.port_map[6790] = b.port
        self.write_record(6790, mtime=2000)
        self.assertEqual(fwd.handle(TOOLS_LIST)["result"], BACKEND_TOOLS)

    def test_421_triggers_reresolve(self):
        old, new = self.backend(), self.backend()
        old.status = 421
        self.write_record(6789)

        def reopen_elsewhere():
            (self.state / "instances" / "6789.json").unlink()
            self.write_record(6791)

        old.on_request = reopen_elsewhere
        resp = self.forwarder({6789: old.port, 6791: new.port}).handle(TOOLS_LIST)
        self.assertEqual(resp["result"], BACKEND_TOOLS)
        self.assertEqual(len(old.requests), 1)
        self.assertEqual(len(new.requests), 1)

    def test_persistent_421_is_backend_error(self):
        b = self.backend()
        b.status = 421
        self.write_record(6789)
        resp = self.forwarder({6789: b.port}).handle(TOOLS_LIST)
        self.assertEqual(resp["error"]["code"], BACKEND_ERROR)
        self.assertIn("421", resp["error"]["message"])
        self.assertEqual(len(b.requests), 2)

    def test_401_rereads_token_once(self):
        b = self.backend()
        self.write_record(6789)
        fwd = self.forwarder({6789: b.port})
        self.assertIn("result", fwd.handle(TOOLS_LIST))  # caches TOKEN
        b.accept_tokens = [NEW_TOKEN]
        self.token_path.write_text(NEW_TOKEN, encoding="utf-8")
        resp = fwd.handle(TOOLS_LIST)
        self.assertEqual(resp["result"], BACKEND_TOOLS)
        self.assertEqual([r[0]["Authorization"] for r in b.requests[1:]],
                         ["Bearer " + TOKEN, "Bearer " + NEW_TOKEN])
        b.accept_tokens = ["something-else"]
        n = len(b.requests)
        resp = fwd.handle(TOOLS_LIST)
        self.assertEqual(resp["error"]["code"], TOKEN_ERROR)
        self.assertEqual(len(b.requests), n + 2)

    def test_missing_token_file(self):
        b = self.backend()
        self.write_record(6789)
        self.token_path.unlink()
        resp = self.forwarder({6789: b.port}).handle(TOOLS_LIST)
        self.assertEqual(resp["error"]["code"], TOKEN_ERROR)
        self.assertIn(str(self.token_path), resp["error"]["message"])
        self.assertEqual(b.requests, [])

    def test_empty_token_file(self):
        b = self.backend()
        self.write_record(6789)
        self.token_path.write_text("  \n", encoding="utf-8")
        resp = self.forwarder({6789: b.port}).handle(TOOLS_LIST)
        self.assertEqual(resp["error"]["code"], TOKEN_ERROR)

    def test_http_500_is_backend_error_with_port_and_status(self):
        b = self.backend()
        b.status = 500
        self.write_record(6789)
        resp = self.forwarder({6789: b.port}).handle(TOOLS_LIST)
        self.assertEqual(resp["id"], 1)
        self.assertEqual(resp["error"]["code"], BACKEND_ERROR)
        self.assertIn("6789", resp["error"]["message"])
        self.assertIn("500", resp["error"]["message"])
        self.assertEqual(len(b.requests), 1)

    def test_read_timeout_is_not_retried(self):
        b = self.backend()
        b.delay = 0.6
        self.write_record(6789)
        resp = self.forwarder({6789: b.port}, timeout=0.2).handle(TOOLS_CALL)
        self.assertEqual(resp["error"]["code"], BACKEND_ERROR)
        self.assertIn("6789", resp["error"]["message"])
        self.assertEqual(len(b.requests), 1)

    def test_ignores_http_proxy_environment(self):
        b = self.backend()
        self.write_record(6789)
        saved = {k: os.environ.get(k) for k in ("HTTP_PROXY", "http_proxy", "NO_PROXY", "no_proxy")}
        try:
            for k in saved:
                os.environ.pop(k, None)
            os.environ["HTTP_PROXY"] = os.environ["http_proxy"] = f"http://127.0.0.1:{self.dead_port}"
            resp = self.forwarder({6789: b.port}).handle(TOOLS_LIST)
        finally:
            for k, v in saved.items():
                if v is None:
                    os.environ.pop(k, None)
                else:
                    os.environ[k] = v
        self.assertEqual(resp["result"], BACKEND_TOOLS)


class _Sink:
    """Thread-safe binary stdout replacement that records flushed output."""

    def __init__(self):
        self.lock = threading.Lock()
        self.buf = bytearray()
        self.changed = threading.Condition(self.lock)

    def write(self, data):
        with self.lock:
            self.buf += data
            self.changed.notify_all()
        return len(data)

    def flush(self):
        pass

    def lines(self):
        with self.lock:
            return bytes(self.buf).split(b"\n")[:-1]

    def wait_for_lines(self, n, timeout=3.0):
        deadline = time.monotonic() + timeout
        with self.lock:
            while bytes(self.buf).count(b"\n") < n:
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                self.changed.wait(left)
        return self.lines()


class ServeTest(ForwardingTestBase):
    def test_serve_parallel_and_utf8(self):
        b = self.backend()
        b.delay = 0.2
        b.reply = lambda m: {"content": [{"type": "text", "text": "ä€"}]}
        self.write_record(6789)
        stdin = io.BytesIO(b"".join(
            json.dumps({"jsonrpc": "2.0", "id": i, "method": "tools/call",
                        "params": {"name": "x", "arguments": {}}}).encode() + b"\n"
            for i in range(1, 9)))
        out = _Sink()
        start = time.monotonic()
        serve(stdin, out, self.forwarder({6789: b.port}), workers=4, poll_interval=0.05)
        elapsed = time.monotonic() - start
        raw = bytes(out.buf)
        self.assertNotIn(b"\r\n", raw)
        lines = out.lines()
        self.assertEqual(len(lines), 8)
        msgs = [json.loads(line.decode("utf-8")) for line in lines]
        self.assertEqual({m["id"] for m in msgs}, set(range(1, 9)))
        for m in msgs:
            self.assertEqual(m["result"]["content"][0]["text"], "ä€")
        self.assertIn("ä€".encode("utf-8"), raw)
        self.assertLess(elapsed, 8 * 0.2)

    def test_serve_parse_error_and_silent_notification(self):
        b = self.backend()
        self.write_record(6789)
        stdin = io.BytesIO(b"{oops\n\n   \n"
                           b'{"jsonrpc":"2.0","method":"notifications/initialized"}\r\n')
        out = _Sink()
        serve(stdin, out, self.forwarder({6789: b.port}), poll_interval=0.05)
        lines = out.lines()
        self.assertEqual(len(lines), 1, lines)
        msg = json.loads(lines[0])
        self.assertIsNone(msg["id"])
        self.assertEqual(msg["error"]["code"], -32700)
        self.assertEqual(len(b.requests), 1)  # the notification was still forwarded

    def test_serve_rejects_batches_and_non_objects(self):
        stdin = io.BytesIO(b'[{"jsonrpc":"2.0","id":1,"method":"tools/list"}]\n42\n')
        out = _Sink()
        serve(stdin, out, self.forwarder({}), poll_interval=0.05)
        msgs = [json.loads(line) for line in out.lines()]
        self.assertEqual(len(msgs), 2)
        for m in msgs:
            self.assertIsNone(m["id"])
            self.assertEqual(m["error"]["code"], -32600)
        self.assertIn("Batch requests are not supported", [m["error"]["message"] for m in msgs])

    def test_list_changed_sent_when_window_appears(self):
        b = self.backend()
        r, w = os.pipe()
        stdin = os.fdopen(r, "rb")
        out = _Sink()
        t = threading.Thread(target=serve, args=(stdin, out, self.forwarder({6789: b.port})),
                             kwargs={"poll_interval": 0.05}, daemon=True)
        t.start()
        try:
            os.write(w, json.dumps(TOOLS_LIST).encode() + b"\n")
            lines = out.wait_for_lines(1)
            self.assertEqual(json.loads(lines[0])["result"], {"tools": []})
            time.sleep(0.2)
            self.assertEqual(len(out.lines()), 1)  # nothing while there is no window
            self.write_record(6789)
            lines = out.wait_for_lines(2)
            self.assertEqual(json.loads(lines[1]),
                             {"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            time.sleep(0.3)
            self.assertEqual(len(out.lines()), 2)  # exactly once
            os.write(w, json.dumps(TOOLS_LIST).encode() + b"\n")
            lines = out.wait_for_lines(3)
            self.assertEqual(json.loads(lines[2])["result"], BACKEND_TOOLS)
        finally:
            os.close(w)
            t.join(5)
            stdin.close()
        self.assertFalse(t.is_alive())

    def test_cli_subprocess_roundtrip(self):
        proc = subprocess.run(
            [sys.executable, "-I", PROXY, "--state-dir", str(self.state), "--project-dir", self.project_dir],
            input=b'{"jsonrpc":"2.0","id":1,"method":"tools/list"}\n',
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertTrue(proc.stdout.endswith(b"\n"))
        self.assertNotIn(b"\r", proc.stdout)
        lines = proc.stdout.split(b"\n")[:-1]
        self.assertEqual(len(lines), 1, proc.stdout)
        self.assertEqual(json.loads(lines[0]), {"jsonrpc": "2.0", "id": 1, "result": {"tools": []}})
        self.assertIn(self.project_dir, proc.stderr.decode("utf-8", "replace"))

    def test_cli_subprocess_utf8_roundtrip(self):
        b = self.backend()
        b.reply = lambda m: {"content": [{"type": "text", "text": m["params"]["arguments"]["t"]}]}
        # The CLI cannot use the _url hook, so serve through a tiny wrapper script instead.
        wrapper = Path(self.tmp) / "run_proxy.py"
        wrapper.write_text(
            "import sys\n"
            f"sys.path.insert(0, {os.path.dirname(PROXY)!r})\n"
            "import intellij_mcp_proxy as p\n"
            f"p.Forwarder._url = lambda self, port: 'http://127.0.0.1:{b.port}/mcp'\n"
            "sys.exit(p.main())\n", encoding="utf-8")
        self.write_record(6789, pid=os.getpid())  # the CLI uses the real liveness check
        msg = {"jsonrpc": "2.0", "id": 5, "method": "tools/call",
               "params": {"name": "x", "arguments": {"t": "Grüße ä€ 𝄞"}}}
        proc = subprocess.run(
            [sys.executable, "-I", str(wrapper), "--state-dir", str(self.state),
             "--project-dir", self.project_dir],
            input=json.dumps(msg, ensure_ascii=False).encode("utf-8") + b"\n",
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertNotIn(b"\r", proc.stdout)
        resp = json.loads(proc.stdout.decode("utf-8"))
        self.assertEqual(resp["result"]["content"][0]["text"], "Grüße ä€ 𝄞")


if __name__ == "__main__":
    unittest.main()
