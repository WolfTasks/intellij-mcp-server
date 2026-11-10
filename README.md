# IntelliJ MCP Server

An IntelliJ IDEA plugin that exposes the open project to MCP clients such as Claude Code: project
structure, file contents, dependencies, symbol search and diagnostics. Each open (trusted) project
gets its own HTTP server on `127.0.0.1`, on the first free port in `6789–6800`, at path `/mcp`.

Requires IntelliJ IDEA 2024.3 or newer.

## Client setup (Claude Code)

The plugin runs one HTTP server per open, trusted project and ships a small stdio proxy
(`intellij_mcp_proxy.py`, Python 3.9+ standard library only) that connects Claude Code to the
server of the right IDE window. `python3` must be on your `PATH`.

The server requires a bearer token. It is generated on first start and stored in
`~/.intellij-mcp-server/token` (Windows: `%USERPROFILE%\.intellij-mcp-server\token`), readable
only by your user account. The proxy reads it for you; you never put it into a config file.

The directory `~/.intellij-mcp-server/` is created with mode `0700` and the token file with `0600`
on macOS/Linux. On Windows the directory gets an ACL with a single entry for your account,
protected against inheritance from the profile folder (no SYSTEM/Administrators/Users entries), and
everything inside inherits that entry. To check: `icacls "%USERPROFILE%\.intellij-mcp-server"`
should list only `<COMPUTER-or-DOMAIN>\<you>:(OI)(CI)(F)`.

### Recommended: stdio proxy (user scope, works for every project)

The plugin installs the proxy to `~/.intellij-mcp-server/bin/intellij_mcp_proxy.py` when the IDE
starts the server (after the first start of a trusted project) and refreshes it on plugin updates.
Register it once for your user:

- Windows:
  ```sh
  claude mcp add intellij -s user -- python3 -I C:/Users/<you>/.intellij-mcp-server/bin/intellij_mcp_proxy.py
  ```
  If `python3` is not on your `PATH` (python.org installs provide `python`/`py`; the Microsoft
  Store `python3` alias may just open the Store), use `py -3 -I <path>` as the command instead.
- macOS/Linux:
  ```sh
  claude mcp add intellij -s user -- python3 -I "$HOME/.intellij-mcp-server/bin/intellij_mcp_proxy.py"
  ```

Claude Code starts the proxy in the session's working directory. For every request the proxy reads
the discovery records (see below), skips records of dead IDE processes, and forwards the request
to the window whose project directory equals the session directory or contains it (with nested
projects, the innermost one wins). Windows opened or closed later are picked up without restarting
Claude Code.

If no IDE window matches, the proxy still starts: it reports an empty tool list and a message
explaining the situation, and notifies Claude Code (`notifications/tools/list_changed`) as soon as
a matching window opens.

Overrides, in order of precedence for the project directory: `--project-dir DIR`, environment
variable `INTELLIJ_MCP_PROJECT_DIR`, `CLAUDE_PROJECT_DIR`, then the working directory. Other
options: `--state-dir DIR` (environment `INTELLIJ_MCP_SERVER_HOME`) to use a different state
directory, and `--timeout SEC` for the HTTP read timeout. `--state-dir` and
`INTELLIJ_MCP_SERVER_HOME` only move the proxy's view (for tests or non-standard setups); the
plugin's state directory is moved only by the JVM system property
`-Dintellij.mcp.server.home=...` (IDE VM options). Both must point to the same directory.

Run `/mcp` in Claude Code to check the connection.

Note: an entry named `intellij` in local scope (`~/.claude.json`, per project) or project scope
(`.mcp.json`) shadows the user-scope entry of the same name. Remove such entries, otherwise the
proxy is not used there.

### Direct connection (one project)

Without the proxy you can point Claude Code at a single server. Export the token as
`INTELLIJ_MCP_TOKEN` (Windows PowerShell: `setx INTELLIJ_MCP_TOKEN (Get-Content "$env:USERPROFILE\.intellij-mcp-server\token")`;
macOS/Linux: `export INTELLIJ_MCP_TOKEN="$(cat ~/.intellij-mcp-server/token)"`), open a new terminal,
and add to the project's `.mcp.json`:

```json
{
  "mcpServers": {
    "intellij": {
      "type": "http",
      "url": "http://localhost:6789/mcp",
      "headers": {
        "Authorization": "Bearer ${INTELLIJ_MCP_TOKEN}",
        "X-IntelliJ-Project": "MyProject"
      }
    }
  }
}
```

`X-IntelliJ-Project` is optional but recommended: set it to the project name as shown in the IDE.
The server then answers `421 Misdirected Request` if this port belongs to a different project,
instead of silently serving another project's files. Project names with non-ASCII characters
can be sent percent-encoded (UTF-8). The port of a project depends on the order in which projects
were opened, so this only suits a single-project setup.

### Which port belongs to which project?

The first project gets port 6789, the next one 6790, and so on, in the order they open. The
IDE notification on start shows the port, and every running server writes a discovery record to
`~/.intellij-mcp-server/instances/<port>.json`:

```json
{"port":6790,"url":"http://127.0.0.1:6790/mcp","projectName":"MyProject","projectPath":"/home/me/MyProject","pid":12345}
```

The record is removed when the project closes. A record left over from a crashed IDE is overwritten
the next time that port is used; compare `pid` with the running IDE if in doubt.

## Migration from versions without authentication

Plugin version 1.1.0 introduces the token. Earlier versions accepted any local request. Now every request without a valid
`Authorization: Bearer <token>` header gets `401 Unauthorized`, so existing `.mcp.json` entries stop
working until they are updated:

1. Update the plugin and restart the IDE (creates `~/.intellij-mcp-server/token`).
2. Either switch to the stdio proxy (see "Client setup"; no token handling needed), or keep the
   direct connection: set `INTELLIJ_MCP_TOKEN`, open a new terminal, and add the `headers` block
   to every `.mcp.json` that points at this server (optionally `X-IntelliJ-Project`).
3. Restart Claude Code and check `/mcp`.

To rotate the token, delete the token file and restart the IDE; then update `INTELLIJ_MCP_TOKEN` if you use the direct connection (the proxy picks up the new token by itself).

## Migration to 1.2.0 (new plugin id)

Version 1.2.0 moves the plugin from the placeholder id `com.example.intellij-mcp-server` to
`io.github.wolftasks.intellij-mcp-server` (vendor "WolfTasks"). The IDE treats this as a different plugin, so it
does not update in place:

1. Uninstall the old "IntelliJ MCP Server" (id `com.example.intellij-mcp-server`) in Settings -> Plugins and restart the IDE.
2. Install 1.2.0 (from the custom repository, see "Updates", or from disk) and restart again.

Do not keep both installed at the same time: they would fight over ports 6789-6800 and the notification group.
Your token and the stdio proxy setup stay valid.

## Updates

Releases are published on GitHub. To get updates inside the IDE like a Marketplace plugin, register the
custom repository once:

1. Settings -> Plugins -> gear icon -> Manage Plugin Repositories -> add
   `https://github.com/WolfTasks/intellij-mcp-server/releases/latest/download/updatePlugins.xml`
2. Install or update "IntelliJ MCP Server" from the Plugins tab as usual.

The stdio proxy is shipped inside the plugin, so a plugin update also updates the proxy.

### Releasing (maintainers)

1. Bump `version` in `build.gradle.kts` and open a PR.
2. Merge it into `master`.
3. Push the tag `vX.Y.Z` (matching `version`) on `master`. The `Release` workflow checks the tag against the
   version, runs the tests, builds the ZIP and creates the GitHub release with the ZIP, `updatePlugins.xml`
   and `intellij_mcp_proxy.py`.

## Security model

- Binds to `127.0.0.1` only; serves `POST /mcp` only.
- `Host` must be `127.0.0.1`, `localhost` or `[::1]` with the server's port; requests with an
  `Origin` header are refused (DNS rebinding / browser cross-site requests).
- Bearer token required (256 bit random, compared in constant time).
- `Content-Type: application/json` only, request body at most 1 MiB.
- `read_file` only reads files inside the project's content roots (symlinks resolved).
- The server does not start for untrusted projects.

The token protects against other OS users on the same machine and against anything that can send
requests to localhost without reading your files (browsers, sandboxed tools). It does not protect
against programs running under your own account: they can read the token file — and the project
files — directly.

Port squatting: if another program already listens on one of the ports when the IDE starts, the
IDE moves to the next free port, and a client configured for the occupied port would send its token
to that program, which could then use it against the real server. The plugin detects this case:
a busy port without a live discovery record from an IntelliJ MCP server triggers a warning
notification ("port … is used by another program"). If you see it, find out what holds the port
before connecting, and rotate the token if a client already connected there.

## Development

```sh
./gradlew test buildPlugin
```

The plugin ZIP ends up in `build/distributions/`.

## License

Released under the [MIT License](LICENSE).
