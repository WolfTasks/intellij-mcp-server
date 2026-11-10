#!/usr/bin/env python3
"""stdio MCP proxy for the IntelliJ MCP server plugin (stdlib only, Python >= 3.9).

Each IntelliJ project window that is trusted runs its own HTTP MCP server on a
port in 6789..6800 and publishes a record ``<state dir>/instances/<port>.json``
(one line of JSON)::

    {"port":6789,"url":"http://127.0.0.1:6789/mcp","projectName":"FeelingsDiary",
     "projectPath":"C:/Users/Admin/IdeaProjects/FeelingsDiary","pid":19812}

The proxy speaks newline-delimited JSON-RPC (MCP stdio transport) on
stdin/stdout. For every message it re-reads those records, checks whether the
owning IDE process is alive, picks the window that serves the Claude session's
project directory, and forwards the message via HTTP POST. The ``url`` field of
a record is deliberately ignored; the URL is always built as
``http://127.0.0.1:<port>/mcp`` from the validated port, so the token never
goes to a host taken from a file. stdout carries JSON-RPC only; logs go to
stderr.

Usage: ``python3 intellij_mcp_proxy.py [--project-dir DIR] [--state-dir DIR] [--timeout SECONDS]``
"""
from __future__ import annotations

import argparse
import http.client
import json
import ntpath
import os
import posixpath
import socket
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Callable, Mapping

IS_WINDOWS: bool = os.name == "nt"
PORTS = range(6789, 6801)
VERSION = "1.2.2"

NO_WINDOW = -32001
BACKEND_ERROR = -32002
TOKEN_ERROR = -32003
PARSE_ERROR = -32700
INVALID_REQUEST = -32600
INTERNAL_ERROR = -32603

PROTOCOL_VERSION = "2024-11-05"
LIST_CHANGED = {"jsonrpc": "2.0", "method": "notifications/tools/list_changed"}

_LONG_PREFIX = "\\\\?\\"  # \\?\C:\...
_UNC_PREFIX = "\\\\?\\UNC\\"  # \\?\UNC\server\share
_MAX_PID = 0xFFFFFFFF  # Windows PIDs are DWORDs; ctypes would silently truncate larger values


def _log(message: str) -> None:
    print(f"intellij-mcp-proxy: {message}", file=sys.stderr, flush=True)


def default_state_dir(env: Mapping[str, str]) -> Path:
    home = env.get("INTELLIJ_MCP_SERVER_HOME")
    if home:
        return Path(home)
    return Path.home() / ".intellij-mcp-server"


def resolve_project_dir(cli_dir: str | None, env: Mapping[str, str], cwd: str) -> str:
    """--project-dir > INTELLIJ_MCP_PROJECT_DIR > CLAUDE_PROJECT_DIR > cwd."""
    if cli_dir:
        return cli_dir
    for key in ("INTELLIJ_MCP_PROJECT_DIR", "CLAUDE_PROJECT_DIR"):
        value = env.get(key)
        if value:
            return value
    return cwd


def normalize_path(path: str, windows: bool = IS_WINDOWS) -> str:
    """Absolute, symlink-resolved (if it exists), '/'-separated, no trailing '/'.

    On Windows the result is also case-folded. Path flavour follows ``windows``
    (ntpath vs posixpath), not the host OS, so it can be tested anywhere.
    """
    mod = ntpath if windows else posixpath
    result = mod.abspath(path)
    if windows == IS_WINDOWS:
        try:
            if os.path.exists(result):
                result = os.path.realpath(result)
        except OSError:
            pass
    if windows:
        if result.startswith(_UNC_PREFIX):
            result = "\\\\" + result[len(_UNC_PREFIX):]
        elif result.startswith(_LONG_PREFIX):
            result = result[len(_LONG_PREFIX):]
        result = result.replace("\\", "/")
    if len(result) > 1 and result.endswith("/") and not (windows and len(result) == 3 and result[1] == ":"):
        result = result.rstrip("/") or "/"
    if windows:
        result = result.casefold()
    return result


def is_within(child: str, parent: str, windows: bool = IS_WINDOWS) -> bool:
    """True if child equals parent or lies below it (path boundary, not string prefix)."""
    c = normalize_path(child, windows)
    p = normalize_path(parent, windows)
    if c == p:
        return True
    boundary = p if p.endswith("/") else p + "/"
    return c.startswith(boundary)


def is_pid_alive(pid: int) -> bool:
    """Liveness check that never signals the process (os.kill would terminate it on Windows)."""
    if pid <= 0 or pid > _MAX_PID:
        return False
    if IS_WINDOWS:
        return _is_pid_alive_windows(pid)
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def _is_pid_alive_windows(pid: int) -> bool:
    import ctypes
    from ctypes import wintypes

    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    STILL_ACTIVE = 259
    ERROR_ACCESS_DENIED = 5

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel32.OpenProcess.restype = ctypes.c_void_p
    kernel32.GetExitCodeProcess.argtypes = [ctypes.c_void_p, ctypes.POINTER(wintypes.DWORD)]
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = [ctypes.c_void_p]
    kernel32.CloseHandle.restype = wintypes.BOOL

    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not handle:
        return ctypes.get_last_error() == ERROR_ACCESS_DENIED
    try:
        code = wintypes.DWORD()
        if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
            return False
        return code.value == STILL_ACTIVE
    finally:
        kernel32.CloseHandle(handle)


@dataclass(frozen=True)
class Instance:
    port: int
    project_name: str
    project_path: str
    pid: int
    mtime: float


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def load_instances(instances_dir: Path) -> list[Instance]:
    """Read all valid ``<port>.json`` records; invalid ones are skipped with a stderr log."""
    result: list[Instance] = []
    try:
        files = sorted(Path(instances_dir).glob("*.json"))
    except OSError:
        return result
    for file in files:
        try:
            mtime = file.stat().st_mtime
            data = json.loads(file.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                raise ValueError("not an object")
            port, name, path, pid = (data.get("port"), data.get("projectName"),
                                     data.get("projectPath"), data.get("pid"))
            if not _is_int(port) or port not in PORTS:
                raise ValueError(f"port {port!r} not allowed")
            if not isinstance(name, str) or not isinstance(path, str) or not path:
                raise ValueError("projectName/projectPath missing")
            if not _is_int(pid):
                raise ValueError("pid missing")
            result.append(Instance(port, name, path, pid, mtime))
        except (OSError, ValueError) as e:  # JSONDecodeError and UnicodeDecodeError are ValueErrors
            _log(f"ignoring {file.name}: {e}")
    return result


def select_instance(
    instances: list[Instance],
    project_dir: str,
    *,
    alive: Callable[[int], bool] = is_pid_alive,
    exclude_ports: frozenset[int] = frozenset(),
    windows: bool = IS_WINDOWS,
) -> Instance | None:
    """Pick the live window whose project contains project_dir: longest path, then newest mtime."""
    best: Instance | None = None
    best_key: tuple[int, float] | None = None
    for inst in instances:
        if inst.port in exclude_ports or inst.port not in PORTS:
            continue
        if not is_within(project_dir, inst.project_path, windows):
            continue
        if not alive(inst.pid):
            continue
        key = (len(normalize_path(inst.project_path, windows)), inst.mtime)
        if best_key is None or key > best_key:
            best, best_key = inst, key
    return best


# ---------------------------------------------------------------------------
# Forwarding
# ---------------------------------------------------------------------------


def no_window_message(project_dir: str, open_windows: list[Instance]) -> str:
    listing = ", ".join(f"{w.project_name} ({w.project_path}) :{w.port}" for w in open_windows) or "none"
    return (f"No IntelliJ window is open for '{project_dir}'. Open this project in IntelliJ IDEA "
            "(IntelliJ MCP Server plugin installed, project trusted); the proxy picks it up "
            f"automatically. Open windows: {listing}")


def _dumps(obj: Any) -> bytes:
    try:
        return json.dumps(obj, ensure_ascii=False).encode("utf-8")
    except UnicodeEncodeError:  # lone surrogates from \ud800-style escapes: keep them escaped
        return json.dumps(obj).encode("ascii")


def _result(msg_id: Any, result: Any) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def _error(msg_id: Any, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}


class _ConnectFailed(Exception):
    """The request never reached the server (refused, connect timeout): safe to send elsewhere."""


class _HttpStatus(Exception):
    def __init__(self, status: int):
        super().__init__(f"HTTP {status}")
        self.status = status


class _BackendFailed(Exception):
    """The request may have reached the server but no usable answer came back: never retried."""


class _TokenMissing(Exception):
    pass


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
        return None  # a redirect would carry the bearer token to another URL; 3xx becomes an HTTPError


_UNSET = object()


class Forwarder:
    """Resolves the IDE window per message and forwards JSON-RPC messages to it over HTTP."""

    def __init__(self, state_dir: Path, project_dir: str, timeout: float = 120.0,
                 alive: Callable[[int], bool] = is_pid_alive):
        self.state_dir = Path(state_dir)
        self.project_dir = project_dir
        self.timeout = timeout
        self.alive = alive
        self.token_path = self.state_dir / "token"
        #: Called whenever a message found no usable window (serve() starts its poll thread here).
        self.on_no_backend: Callable[[], None] | None = None
        # ProxyHandler({}): localhost traffic must never go through HTTP(S)_PROXY.
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
        self._lock = threading.Lock()
        self._token: str | None = None
        self._current_port: Any = _UNSET
        # port -> (pid, mtime) of records whose port refused connections; skipped until rewritten
        self._refused: dict[int, tuple[int, float]] = {}

    # -- hooks -------------------------------------------------------------

    def _url(self, port: int) -> str:
        return f"http://127.0.0.1:{port}/mcp"

    # -- resolution --------------------------------------------------------

    def _resolve(self, exclude: frozenset[int] = frozenset()) -> Instance | None:
        instances = load_instances(self.state_dir / "instances")
        current = {i.port: (i.pid, i.mtime) for i in instances}
        with self._lock:
            self._refused = {p: k for p, k in self._refused.items() if current.get(p) == k}
            skip = frozenset(exclude) | frozenset(self._refused)
        chosen = select_instance(instances, self.project_dir, alive=self.alive, exclude_ports=skip)
        self._note_selection(chosen)
        return chosen

    def _note_selection(self, chosen: Instance | None) -> None:
        port = chosen.port if chosen else None
        with self._lock:
            if port == self._current_port:
                return
            self._current_port = port
        if chosen:
            _log(f"using IntelliJ window '{chosen.project_name}' ({chosen.project_path}) on port {port}")
        else:
            _log(f"no IntelliJ window open for {self.project_dir}")

    def has_backend(self) -> bool:
        return self._resolve() is not None

    def _open_windows(self) -> list[Instance]:
        return [i for i in load_instances(self.state_dir / "instances") if self.alive(i.pid)]

    # -- token -------------------------------------------------------------

    def _read_token(self, use_cache: bool) -> str:
        if use_cache:
            with self._lock:
                if self._token:
                    return self._token
        try:
            token = self.token_path.read_text(encoding="utf-8").strip()
        except (OSError, ValueError):
            token = ""
        if not token or not token.isascii() or not token.isprintable():
            raise _TokenMissing(
                f"IntelliJ MCP token file {self.token_path} is missing, empty or invalid. "
                "It is created by the IntelliJ MCP Server plugin when a project window opens.")
        with self._lock:
            self._token = token
        return token

    # -- handling ----------------------------------------------------------

    def handle(self, message: dict) -> dict | None:
        """Answer one JSON-RPC message; None for notifications (no "id" key)."""
        msg_id = message.get("id")
        try:
            response = self._dispatch(message, msg_id)
        except Exception as e:  # never let one message kill the proxy
            _log(f"internal error while handling {message.get('method')!r}: {e!r}")
            response = _error(msg_id, INTERNAL_ERROR, f"Internal proxy error: {e}")
        return response if "id" in message else None

    def _no_backend(self, message: dict, msg_id: Any) -> dict | None:
        if self.on_no_backend is not None:
            self.on_no_backend()
        if "id" not in message:
            return None
        method = message.get("method")
        if method == "tools/list":
            return _result(msg_id, {"tools": []})
        if method == "ping":
            return _result(msg_id, {})
        text = no_window_message(self.project_dir, self._open_windows())
        if method == "initialize":
            return _result(msg_id, {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": True}},
                "serverInfo": {"name": "intellij-mcp-proxy", "version": VERSION},
                "instructions": text,
            })
        return _error(msg_id, NO_WINDOW, text)

    def _dispatch(self, message: dict, msg_id: Any) -> dict | None:
        inst = self._resolve()
        if inst is None:
            return self._no_backend(message, msg_id)
        try:
            token = self._read_token(use_cache=True)
        except _TokenMissing as e:
            return _error(msg_id, TOKEN_ERROR, str(e))
        body = _dumps(message)
        excluded: set[int] = set()
        retried_connect = retried_421 = retried_401 = False
        while True:
            try:
                payload = self._post(inst, body, token)
                break
            except _ConnectFailed as e:
                with self._lock:
                    self._refused[inst.port] = (inst.pid, inst.mtime)
                if retried_connect:
                    return _error(msg_id, BACKEND_ERROR,
                                  f"IntelliJ window on port {inst.port} is not reachable: {e}")
                retried_connect = True
                excluded.add(inst.port)
                _log(f"port {inst.port} not reachable ({e}); re-resolving")
                inst = self._resolve(frozenset(excluded))
                if inst is None:
                    return self._no_backend(message, msg_id)
            except _HttpStatus as e:
                if e.status == 421 and not retried_421:
                    retried_421 = True
                    _log(f"port {inst.port} serves another project (HTTP 421); re-resolving")
                    inst = self._resolve(frozenset(excluded))
                    if inst is None:
                        return self._no_backend(message, msg_id)
                elif e.status == 401:
                    if retried_401:
                        return _error(msg_id, TOKEN_ERROR,
                                      f"IntelliJ window on port {inst.port} rejected the token from "
                                      f"{self.token_path} (HTTP 401)")
                    retried_401 = True
                    try:
                        token = self._read_token(use_cache=False)
                    except _TokenMissing as t:
                        return _error(msg_id, TOKEN_ERROR, str(t))
                else:
                    return _error(msg_id, BACKEND_ERROR,
                                  f"IntelliJ window on port {inst.port} answered HTTP {e.status}")
            except _BackendFailed as e:
                return _error(msg_id, BACKEND_ERROR, f"IntelliJ window on port {inst.port}: {e}")
        if not isinstance(payload, dict):
            return _error(msg_id, BACKEND_ERROR,
                          f"IntelliJ window on port {inst.port} sent an invalid JSON-RPC response")
        if message.get("method") == "initialize":
            _inject_list_changed(payload)
        return payload

    def _post(self, inst: Instance, body: bytes, token: str) -> Any:
        request = urllib.request.Request(self._url(inst.port), data=body, method="POST", headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "X-IntelliJ-Project": urllib.parse.quote(inst.project_name, safe=""),
        })
        try:
            response = self._opener.open(request, timeout=self.timeout)
        except urllib.error.HTTPError as e:  # subclass of URLError: check first
            e.close()
            raise _HttpStatus(e.code) from None
        except urllib.error.URLError as e:
            # urllib wraps any OSError while connecting OR sending the request in URLError. Only a
            # refused/timed-out connect proves the request never arrived; a reset or broken pipe
            # while sending may have been processed, so it is not retried and not quarantined.
            if isinstance(e.reason, (ConnectionRefusedError, socket.timeout, TimeoutError)):
                raise _ConnectFailed(_describe(e.reason)) from None
            raise _BackendFailed(f"request failed ({_describe(e.reason) if isinstance(e.reason, BaseException) else e.reason})") from None
        except (OSError, http.client.HTTPException) as e:
            # Raised while waiting for the response (read timeout, reset): the call may be running.
            raise _BackendFailed(f"no response ({_describe(e)})") from None
        with response:
            try:
                status = response.status
                data = response.read()
            except (OSError, http.client.HTTPException) as e:
                raise _BackendFailed(f"response interrupted ({_describe(e)})") from None
        if status != 200:
            raise _HttpStatus(status)
        try:
            return json.loads(data)
        except ValueError:
            raise _BackendFailed("response is not valid JSON") from None


def _describe(e: BaseException) -> str:
    if isinstance(e, (socket.timeout, TimeoutError)):
        return "timed out"
    return str(e) or type(e).__name__


def _inject_list_changed(payload: dict) -> None:
    result = payload.get("result")
    if not isinstance(result, dict):
        return
    caps = result.get("capabilities")
    if not isinstance(caps, dict):
        caps = result["capabilities"] = {}
    tools = caps.get("tools")
    if not isinstance(tools, dict):
        tools = caps["tools"] = {}
    tools["listChanged"] = True


# ---------------------------------------------------------------------------
# stdio loop
# ---------------------------------------------------------------------------


class _ListChangedPoller:
    """Polls while no window is known; emits list_changed once a window shows up, then stops."""

    def __init__(self, forwarder: Forwarder, write: Callable[[dict], None], interval: float):
        self._forwarder = forwarder
        self._write = write
        self._interval = interval
        self._lock = threading.Lock()
        self._stopped = threading.Event()
        self._thread: threading.Thread | None = None
        self._missed = False  # a message found no window since the last check

    def trigger(self) -> None:
        with self._lock:
            self._missed = True
            if self._thread is None and not self._stopped.is_set():
                self._thread = threading.Thread(target=self._run, name="intellij-mcp-proxy-poll", daemon=True)
                self._thread.start()

    def _run(self) -> None:
        while not self._stopped.wait(self._interval):
            with self._lock:
                self._missed = False
            try:
                found = self._forwarder.has_backend()
            except Exception as e:  # keep polling
                _log(f"poll failed: {e!r}")
                continue
            if not found:
                continue
            self._write(LIST_CHANGED)
            with self._lock:
                if not self._missed:  # otherwise a request answered "no window" after our check
                    self._thread = None
                    return
        with self._lock:
            self._thread = None

    def stop(self) -> None:
        self._stopped.set()


def serve(stdin: BinaryIO, stdout: BinaryIO, forwarder: Forwarder, workers: int = 4,
          poll_interval: float = 2.0) -> None:
    """Read newline-delimited JSON-RPC from stdin until EOF; write responses to stdout."""
    write_lock = threading.Lock()

    def write(obj: dict) -> None:
        data = _dumps(obj) + b"\n"
        with write_lock:
            try:
                stdout.write(data)
                stdout.flush()
            except (OSError, ValueError) as e:
                _log(f"cannot write to stdout: {e}")

    def run(message: dict) -> None:
        response = forwarder.handle(message)
        if response is not None:
            write(response)

    poller = _ListChangedPoller(forwarder, write, poll_interval)
    previous_hook = forwarder.on_no_backend
    forwarder.on_no_backend = poller.trigger
    executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="intellij-mcp-proxy")
    try:
        for raw in iter(stdin.readline, b""):
            line = raw.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except ValueError as e:  # includes invalid UTF-8
                write(_error(None, PARSE_ERROR, f"Parse error: {e}"))
                continue
            if isinstance(message, list):
                write(_error(None, INVALID_REQUEST, "Batch requests are not supported"))
                continue
            if not isinstance(message, dict):
                write(_error(None, INVALID_REQUEST, "Invalid Request: expected a JSON object"))
                continue
            executor.submit(run, message)
        executor.shutdown(wait=True)
    except BaseException:
        executor.shutdown(wait=False, cancel_futures=True)
        raise
    finally:
        poller.stop()
        forwarder.on_no_backend = previous_hook


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="intellij_mcp_proxy",
        description="stdio MCP proxy that forwards to the IntelliJ window serving the project directory.")
    parser.add_argument("--project-dir", help="session project directory (default: "
                        "$INTELLIJ_MCP_PROJECT_DIR, $CLAUDE_PROJECT_DIR, current directory)")
    parser.add_argument("--state-dir", help="plugin state directory (default: "
                        "$INTELLIJ_MCP_SERVER_HOME or ~/.intellij-mcp-server)")
    parser.add_argument("--timeout", type=float, default=120.0,
                        help="HTTP timeout per request in seconds (default: 120)")
    args = parser.parse_args(argv)
    state_dir = Path(args.state_dir) if args.state_dir else default_state_dir(os.environ)
    project_dir = resolve_project_dir(args.project_dir, os.environ, os.getcwd())
    _log(f"version {VERSION}; project dir {project_dir}; state dir {state_dir}")
    forwarder = Forwarder(state_dir, project_dir, timeout=args.timeout)
    try:
        serve(sys.stdin.buffer, sys.stdout.buffer, forwarder)
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
