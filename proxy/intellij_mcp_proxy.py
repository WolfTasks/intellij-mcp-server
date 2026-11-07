#!/usr/bin/env python3
"""stdio MCP proxy for the IntelliJ MCP server plugin (stdlib only, Python >= 3.9).

Each IntelliJ project window that is trusted runs its own HTTP MCP server on a
port in 6789..6800 and publishes a record ``<state dir>/instances/<port>.json``
(one line of JSON)::

    {"port":6789,"url":"http://127.0.0.1:6789/mcp","projectName":"FeelingsDiary",
     "projectPath":"C:/Users/Admin/IdeaProjects/FeelingsDiary","pid":19812}

This module contains the resolution part: reading those records, checking
whether the owning IDE process is alive, and choosing the window that serves
the Claude session's project directory. The ``url`` field of a record is
deliberately ignored; callers must build ``http://127.0.0.1:<port>/mcp`` from
the validated port themselves.
"""
from __future__ import annotations

import json
import ntpath
import os
import posixpath
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping

IS_WINDOWS: bool = os.name == "nt"
PORTS = range(6789, 6801)


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
        bs = chr(92)
        if result.startswith(bs * 2 + "?" + bs + "UNC" + bs):
            result = bs * 2 + result[8:]
        elif result.startswith(bs * 2 + "?" + bs):
            result = result[4:]
        result = result.replace(bs, "/")
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
    if pid <= 0:
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
