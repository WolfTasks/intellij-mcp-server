# IntelliJ MCP Server

An IntelliJ IDEA plugin that exposes the open project to MCP clients such as Claude Code: project
structure, file contents, dependencies, symbol search and diagnostics. Each open (trusted) project
gets its own HTTP server on `127.0.0.1`, on the first free port in `6789–6800`, at path `/mcp`.

Requires IntelliJ IDEA 2024.3 or newer.

## Client setup (Claude Code)

The server requires a bearer token. It is generated on first start and stored in
`~/.intellij-mcp-server/token` (Windows: `%USERPROFILE%\.intellij-mcp-server\token`), readable
only by your user account.

The directory `~/.intellij-mcp-server/` is created with mode `0700` and the token file with `0600`
on macOS/Linux. On Windows the directory gets an ACL with a single entry for your account,
protected against inheritance from the profile folder (no SYSTEM/Administrators/Users entries), and
everything inside inherits that entry. To check: `icacls "%USERPROFILE%\.intellij-mcp-server"`
should list only `<COMPUTER-or-DOMAIN>\<you>:(OI)(CI)(F)`.

1. Start the IDE with the plugin once, so the token file exists.
2. Export the token as the environment variable `INTELLIJ_MCP_TOKEN`, in the environment Claude Code
   starts from:

   - Windows (PowerShell, persistent for new terminals):
     ```powershell
     setx INTELLIJ_MCP_TOKEN (Get-Content "$env:USERPROFILE\.intellij-mcp-server\token")
     ```
   - macOS/Linux (add to `~/.bashrc` / `~/.zshrc`):
     ```sh
     export INTELLIJ_MCP_TOKEN="$(cat ~/.intellij-mcp-server/token)"
     ```

   Open a new terminal afterwards.
3. Configure the server in the project's `.mcp.json`. Claude Code expands `${VAR}` in `headers`, so
   the token itself never ends up in the repository:

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
   can be sent percent-encoded (UTF-8).

   If you prefer not to use an environment variable, Claude Code's `headersHelper` can read the file
   directly, e.g. on macOS/Linux:
   `"headersHelper": "printf '{\"Authorization\":\"Bearer %s\"}' \"$(cat ~/.intellij-mcp-server/token)\""`.

Run `/mcp` in Claude Code to check the connection.

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
2. Set `INTELLIJ_MCP_TOKEN` as described above and open a new terminal.
3. Add the `headers` block to every `.mcp.json` that points at this server (and optionally
   `X-IntelliJ-Project`).
4. Restart Claude Code and check `/mcp`.

To rotate the token, delete the token file and restart the IDE; then update `INTELLIJ_MCP_TOKEN`.

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
