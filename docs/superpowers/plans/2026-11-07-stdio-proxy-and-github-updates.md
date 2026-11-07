# stdio-Proxy und GitHub-Update-Pfad – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jede Claude-Code-Session redet automatisch mit dem IntelliJ-Fenster ihres eigenen Projekts (stdio-Proxy statt statischem Port 6789), und Plugin + Proxy werden über ein GitHub-basiertes Custom Plugin Repository aktualisiert.

**Architecture:** Teil A: ein stdlib-only Python-Skript `proxy/intellij_mcp_proxy.py` läuft als user-scope stdio-MCP-Server, löst pro Request über `~/.intellij-mcp-server/instances/*.json` (+ PID-Livecheck) das Fenster zum Session-Verzeichnis auf und leitet JSON-RPC per HTTP mit Bearer-Token und `X-IntelliJ-Project` weiter. Das Skript wird im Plugin-JAR mitgeliefert und vom Plugin beim Start nach `~/.intellij-mcp-server/bin/` installiert – damit aktualisiert jedes Plugin-Update auch den Proxy. Teil B: Tag `v*` → GitHub-Release mit Plugin-ZIP + `updatePlugins.xml`; die stabile URL `…/releases/latest/download/updatePlugins.xml` wird einmalig als Custom Plugin Repository in IntelliJ eingetragen.

**Tech Stack:** Kotlin 2.4.21 / IntelliJ Platform Gradle Plugin 2.20.0 / Gradle 9.8.2 / JDK 21 (Plugin); Python ≥ 3.9, nur Standardbibliothek, `unittest` (Proxy); GitHub Actions + `gh` CLI (Release).

**Spec:** Auftrag von Wolfgang vom 2026-11-07 (Themen A und B, in diesem Plan unter „Ausgangslage“ zusammengefasst). Kein separates Spec-Dokument.

## Ausgangslage (Fakten aus dem Code, Stand b983099 / 1.1.1)

- `McpPluginService` startet pro vertrauenswürdigem Projekt einen `HttpMcpServer` auf dem ersten freien Port in `6789..6800` und schreibt `instances/<port>.json` über `McpAuthStore.writeInstance`. Format (eine Zeile, kein Pretty-Print):
  `{"port":6789,"url":"http://127.0.0.1:6789/mcp","projectName":"FeelingsDiary","projectPath":"C:/Users/Admin/IdeaProjects/FeelingsDiary","pid":19812}`
  `projectPath` ist `project.basePath` (Forward-Slashes, kann theoretisch `null` sein). Alle Fenster eines IDE-Prozesses haben dieselbe `pid`. Beim Schließen wird der Record gelöscht; nach Absturz bleibt er stehen.
- Token: `~/.intellij-mcp-server/token` (eine Zeile, base64url, ≥ 32 Zeichen). Server prüft `Authorization: Bearer`, `Host` ∈ {`127.0.0.1:<port>`, `localhost:<port>`, `[::1]:<port>`}, kein `Origin`, `Content-Type: application/json`, Body ≤ 1 MiB. `X-IntelliJ-Project` (optional, percent-encoded UTF-8) ≠ Projektname → 421.
- Der Server ist zustandslos (keine MCP-Session). Auf Notifications (kein `id`) antwortet er trotzdem mit einem JSON-RPC-Error mit `"id":null` (`Method not found`).
- Test-Leak: `BasePlatformTestCase`-Tests (z. B. `DiagnosticsToolTest."test missing file is reported"`) öffnen ein Light-Project → `postStartupActivity` → `McpPluginService` startet einen echten Server auf einem echten Port und schreibt in das echte `~/.intellij-mcp-server`. Belegt durch die (inzwischen gelöschte) `instances/6791.json` mit `projectName":"missing file is reported"`.
- Repo ist öffentlich, keine Releases, `master` ohne Branch-Protection. `python3` liegt auf dem PATH (3.14; `C:\Users\Admin\AppData\Local\Python\bin\python3.exe` und WindowsApps-Alias), Node 26 und Java 25 sind ebenfalls vorhanden.
- Aktuelle Client-Konfiguration: user-scope `intellij` (http → :6789 + headersHelper) in `~/.claude.json`, zusätzlich ein local-scope-Eintrag `intellij` unter `projects["C:/Users/Admin/IdeaProjects/FileSearch/SearchAndGroup"]`, plus project-scope `.mcp.json` im Repo. Local und project scope **überschatten** user scope – sie müssen bei der Umstellung weg, sonst greift der Proxy dort nicht.

## Architekturentscheidungen

**A1 – Sprache: Python, stdlib-only, eine Datei.** Begründung: `python3` ist da und wird schon vom headersHelper genutzt; Start in ~50 ms (JVM-Variante: 0,3–1 s pro Session-Start plus Fat-JAR mit eigener kotlinx-serialization, zweites Gradle-Subprojekt, eigener Trivy-Scope); keine Abhängigkeiten → nichts für Dependabot/Trivy; eine Datei lässt sich trivial ausliefern. Node wäre gleichwertig schnell, brächte aber eine dritte Sprache ins Repo. Risiko: Python fehlt auf einer anderen Maschine → README nennt das als Voraussetzung.

**A2 – Auslieferung: Plugin installiert den Proxy.** Quelle liegt in `proxy/` (Repo-Root, damit Python-Tests ohne Gradle laufen), `processResources` kopiert sie nach `proxy/intellij_mcp_proxy.py` ins JAR. Beim Serverstart schreibt das Plugin sie nach `~/.intellij-mcp-server/bin/intellij_mcp_proxy.py`, wenn der Inhalt abweicht (atomar, owner-only wie Token). Folge: Plugin-Update ⇒ Proxy-Update, keine zweite Update-Mechanik. Das Skript hängt zusätzlich als Release-Asset am Release (Transparenz, manuelle Installation ohne IDE).

**A3 – Auflösung pro Request.** Kein Cache: jeder Request liest `instances/*.json` neu (eine Handvoll kleiner Dateien) und prüft PIDs. Damit funktionieren „Fenster später geöffnet“, „Fenster geschlossen“ und „Port gewechselt“ ohne Neustart. Match-Regel: Record passt, wenn das normalisierte Projektverzeichnis der Session gleich `projectPath` ist oder darunter liegt (Pfadgrenze, nicht String-Präfix); bei mehreren gewinnt der längste `projectPath`, danach die jüngste Datei-mtime. Nur Ports 6789–6800 werden akzeptiert; die URL wird immer selbst als `http://127.0.0.1:<port>/mcp` gebaut, das Feld `url` des Records wird ignoriert (Token geht nie an einen Host aus einer Datei).

**A4 – Projektverzeichnis der Session.** Reihenfolge: `--project-dir` > Env `INTELLIJ_MCP_PROJECT_DIR` > Env `CLAUDE_PROJECT_DIR` > `os.getcwd()`. Claude Code startet stdio-Server im Arbeitsverzeichnis der Session; Task 5 verifiziert das real (Proxy loggt das gewählte Verzeichnis auf stderr).

**A5 – Kein Fenster offen.** `initialize` wird dann lokal beantwortet (`protocolVersion` `2024-11-05`, `capabilities.tools.listChanged: true`, `instructions` mit der Klartext-Meldung), `tools/list` liefert `{"tools":[]}`, `tools/call` einen JSON-RPC-Error `-32001` mit der Meldung. Ein Hintergrund-Thread pollt alle 2 s und sendet `notifications/tools/list_changed`, sobald ein passendes Fenster auftaucht. Ist ein Fenster da, wird `initialize` weitergeleitet und `listChanged: true` in die Antwort injiziert. (Siehe Entscheidungsfrage 3.)

**A6 – Fehlerbehandlung beim Forward (je höchstens ein Retry):** Connection refused/Timeout beim Verbinden → Port für diesen Request ausschließen, neu auflösen, erneut; 421 → neu auflösen, erneut; 401 → Token-Datei neu lesen, erneut. Danach JSON-RPC-Error `-32002` (Backend-Fehler, mit Port/HTTP-Status) bzw. `-32003` (Token fehlt/abgelehnt). Read-Timeout eines laufenden Tool-Calls wird **nicht** wiederholt (nicht idempotent genug, und es würde die Wartezeit verdoppeln).

**A7 – Test-Leak doppelt schließen:** (a) `McpPluginService` startet im Unit-Test-Modus keinen Server; (b) `McpAuthStore.defaultDir()` respektiert die System-Property `intellij.mcp.server.home`, die `tasks.test` auf `build/tmp/test-mcp-home` setzt. Der Proxy respektiert analog Env `INTELLIJ_MCP_SERVER_HOME` bzw. `--state-dir`.

**B1 – Update-Kanal: Release-Asset statt GitHub Pages.** `https://github.com/WolfTasks/intellij-mcp-server/releases/latest/download/updatePlugins.xml` ist stabil, braucht kein Pages-Setup und kein `gh-pages`-Branch, und das Repo ist öffentlich (anonymer Download funktioniert). IntelliJ folgt dem Redirect. Die XML verweist mit absoluter URL auf das ZIP des jeweiligen Tags.

**B2 – Version bleibt in `build.gradle.kts`.** Der Release-Workflow bricht ab, wenn Tag (`v1.2.0`) und `version` nicht übereinstimmen. Kein Auto-Bump.

**B3 – Plugin-ID:** siehe Entscheidungsfrage 1; der Plan enthält den Wechsel als Task 7, der bei „nein“ entfällt.

## Offene Entscheidungsfragen für Wolfgang

1. **Plugin-ID von `com.example.intellij-mcp-server` auf `io.github.wolftasks.intellij-mcp-server` ändern (Vendor „WolfTasks“)?** Folge: IntelliJ sieht ein anderes Plugin; das alte muss vorher deinstalliert werden (sonst binden beide Ports und registrieren dieselbe Notification-Group → Fehler). **Empfehlung: ja, jetzt, zusammen mit dem ersten Release 1.2.0** – das Repository muss ohnehin einmalig manuell eingetragen werden, später wäre der Wechsel genauso teuer, und `com.example` ist ein Platzhalter (Verifier-Regel `ForbiddenPluginIdPrefix` ist nur stummgeschaltet). Paketnamen `com.example.mcpserver` bleiben (unsichtbar für Nutzer, reiner Diff-Lärm).
2. **Repo-eigene `.mcp.json`: löschen oder auf den Proxy umstellen?** Project scope überschattet user scope; ein stdio-Eintrag mit absolutem Pfad gehört nicht ins Repo. **Empfehlung: löschen** und im README den user-scope-Eintrag dokumentieren.
3. **Verhalten ohne offenes Fenster:** leere Tool-Liste + `list_changed`-Nachmeldung (Plan, A5) oder harter Fehler bei `tools/list` (Claude Code markiert den Server dann als fehlgeschlagen, Neuverbinden per `/mcp`)? **Empfehlung: leere Liste + `list_changed`**, weil die Session dann ohne Eingriff nutzbar wird, sobald das Fenster aufgeht; die Klartext-Meldung steht in `instructions` und in jedem `tools/call`-Fehler.
4. **Python-Pfad in der Client-Konfiguration:** `python3` (wie headersHelper) oder absolut `C:/Users/Admin/AppData/Local/Python/bin/python3.exe`? **Empfehlung: `python3`**, Task 6 prüft vorher, dass Claude Code es ohne Shell startet; nur bei Fehlschlag absolut.
5. **Versionsnummer:** Teil A + B als 1.2.0 (bei ID-Wechsel ggf. 2.0.0). **Empfehlung: 1.2.0** – für Nutzer ist es Neuinstallation, an der Server-API ändert sich nichts.

## Global Constraints

- Python-Proxy: nur Standardbibliothek, lauffähig ab Python 3.9, eine Datei; stdout ausschließlich JSON-RPC (newline-delimited, UTF-8, `\n` ohne CR), Logs nur auf stderr.
- Kotlin: `apiVersion = KOTLIN_2_0` bleibt; keine neuen Runtime-Dependencies im Plugin.
- GitHub Actions: nur per Commit-SHA gepinnte Actions (bestehende Pins wiederverwenden), `permissions` minimal pro Workflow; Release über vorinstalliertes `gh`, keine neue Third-Party-Action.
- Git: Arbeit auf Feature-Branch im Worktree, nie auf `master` committen/pushen, nur explizite Pfade stagen, Commits auf Englisch.
- `~/.claude.json` wird nur in Task 6 und nur nach Wolfgangs ausdrücklichem OK geändert, immer mit Backup.
- Konstanten: Ports `6789..6800`; State-Dir `~/.intellij-mcp-server`; System-Property `intellij.mcp.server.home`; Env `INTELLIJ_MCP_SERVER_HOME`, `INTELLIJ_MCP_PROJECT_DIR`; Header `X-IntelliJ-Project`; Proxy-Datei `bin/intellij_mcp_proxy.py`; Update-URL `https://github.com/WolfTasks/intellij-mcp-server/releases/latest/download/updatePlugins.xml`.

## Review Focus

1. **`os.kill(pid, 0)` auf Windows beendet den Prozess** (Signal ≠ CTRL_C/BREAK → `TerminateProcess`). Liveness muss auf Windows über `OpenProcess`/`GetExitCodeProcess` laufen; ein Livecheck darf die IDE nie töten. → Test in Task 2.
2. **Windows-stdio:** Textmodus macht aus `\n` ein `\r\n` und nutzt die ANSI-Codepage; Umlaute in Dateiinhalten müssen unverändert ankommen. → Subprozess-Test mit `ä€` in Task 3.
3. **Notifications ohne `id`** dürfen keine Zeile auf stdout erzeugen, obwohl der Server mit `"id":null`-Error antwortet. → Test in Task 3.
4. **Pfad-Präfix-Falle und Normalisierung:** `C:/x/Foo` darf nicht auf Session-Verzeichnis `c:\x\FooBar` matchen; Groß/Klein, Backslashes, Trailing Slash, Laufwerksbuchstabe müssen egal sein (nur auf Windows); verschachtelte Projekte → nächster Elternpfad. → Tests in Task 2.
5. **Parallele Requests und wechselnde Fenster:** Claude Code schickt parallele Tool-Calls; Antworten dürfen sich auf stdout nicht verschränken, und ein zwischen zwei Requests geschlossenes/neu geöffnetes Fenster (alter Record mit toter PID oder Port refused) muss sauber neu aufgelöst werden. → Tests in Task 3.

---

## Dateistruktur

| Datei | Verantwortung |
|---|---|
| `proxy/intellij_mcp_proxy.py` (neu) | Kompletter Proxy: Auflösung, Forward, stdio-Loop, CLI |
| `proxy/test_intellij_mcp_proxy.py` (neu) | `unittest`-Tests inkl. Fake-HTTP-Backend und Subprozess-Tests |
| `src/main/kotlin/com/example/mcpserver/auth/McpAuthStore.kt` | + System-Property, + generisches atomares Private-Write, + `bin/` |
| `src/main/kotlin/com/example/mcpserver/proxy/ProxyInstaller.kt` (neu) | Proxy-Ressource nach `bin/` installieren |
| `src/main/kotlin/com/example/mcpserver/McpPluginService.kt` | Unit-Test-Gate, Proxy-Installation, Port-Accessor |
| `src/test/kotlin/.../McpPluginServiceTest.kt`, `.../proxy/ProxyInstallerTest.kt` (neu), `.../auth/McpAuthStoreTest.kt` | Tests |
| `build.gradle.kts` | Test-System-Property, `processResources`, `generateUpdatePluginsXml` |
| `.github/workflows/ci.yml` | + Python-Tests |
| `.github/workflows/release.yml` (neu) | Tag → Release |
| `src/main/resources/META-INF/plugin.xml` | Beschreibung; ggf. ID/Vendor (Task 7) |
| `README.md` | Client-Setup über Proxy, Update-Repository |
| `.mcp.json` | gemäß Entscheidungsfrage 2 |

---

## Teil A – stdio-Proxy

### Task 1: Test-Leak schließen

**Files:**
- Modify: `src/main/kotlin/com/example/mcpserver/auth/McpAuthStore.kt` (`defaultDir`)
- Modify: `src/main/kotlin/com/example/mcpserver/McpPluginService.kt` (`init`, neuer Accessor)
- Modify: `build.gradle.kts` (`tasks.test`)
- Test: `src/test/kotlin/com/example/mcpserver/auth/McpAuthStoreTest.kt`, `src/test/kotlin/com/example/mcpserver/McpPluginServiceTest.kt` (neu)

**Interfaces:**
- Produces: `McpAuthStore.HOME_PROPERTY = "intellij.mcp.server.home"`; `McpAuthStore.defaultDir(): Path` = Property (wenn nicht blank) sonst `~/.intellij-mcp-server`; `McpPluginService.port: Int?` (internal, read-only, = `serverPort`).

- [ ] **Step 1: Failing Tests schreiben**

```kotlin
// McpAuthStoreTest
@Test fun `default dir honors the home system property`() {
    val old = System.getProperty(McpAuthStore.HOME_PROPERTY)
    try {
        System.setProperty(McpAuthStore.HOME_PROPERTY, root.resolve("custom").toString())
        assertEquals(root.resolve("custom"), McpAuthStore.defaultDir())
        System.setProperty(McpAuthStore.HOME_PROPERTY, " ")
        assertEquals(Path.of(System.getProperty("user.home"), ".intellij-mcp-server"), McpAuthStore.defaultDir())
    } finally { if (old == null) System.clearProperty(McpAuthStore.HOME_PROPERTY) else System.setProperty(McpAuthStore.HOME_PROPERTY, old) }
}

// McpPluginServiceTest : BasePlatformTestCase
fun `test no server is started in unit test mode`() {
    assertNull(project.getService(McpPluginService::class.java).port)
}
fun `test tests never write to the real state directory`() {
    assertFalse(McpAuthStore.defaultDir().startsWith(Path.of(System.getProperty("user.home"), ".intellij-mcp-server")))
}
```

- [ ] **Step 2: Laufen lassen** – `./gradlew test --tests '*McpAuthStoreTest*' --tests '*McpPluginServiceTest*'` → FAIL (unresolved `HOME_PROPERTY` / `port`).
- [ ] **Step 3: Implementieren.** `defaultDir()` liest die Property. In `McpPluginService.init` als erster Zweig vor der Trust-Prüfung: im Unit-Test-Modus (`ApplicationManager.getApplication().isUnitTestMode`) nur `LOG.info("MCP server not started: unit test mode")`, keine Notification, kein Serverstart. `tasks.test { systemProperty("intellij.mcp.server.home", layout.buildDirectory.dir("tmp/test-mcp-home").get().asFile.absolutePath) }`.
- [ ] **Step 4: Gesamte Suite** – `./gradlew test` → PASS; danach `ls ~/.intellij-mcp-server/instances/` enthält keine neuen Dateien (nur Records laufender IDEs).
- [ ] **Step 5: Commit** – `git add build.gradle.kts src/main/kotlin/com/example/mcpserver/auth/McpAuthStore.kt src/main/kotlin/com/example/mcpserver/McpPluginService.kt src/test/kotlin/com/example/mcpserver/auth/McpAuthStoreTest.kt src/test/kotlin/com/example/mcpserver/McpPluginServiceTest.kt` → `fix: keep tests away from real ports and the real state directory`

### Task 2: Proxy – Auflösung (Records, Liveness, Matching)

**Files:**
- Create: `proxy/intellij_mcp_proxy.py`
- Test: `proxy/test_intellij_mcp_proxy.py`

**Interfaces (Produces, alle in `intellij_mcp_proxy.py`):**
- `IS_WINDOWS: bool`, `PORTS = range(6789, 6801)`
- `default_state_dir(env: Mapping[str, str]) -> Path` – `INTELLIJ_MCP_SERVER_HOME` sonst `~/.intellij-mcp-server`
- `resolve_project_dir(cli_dir: str | None, env: Mapping[str, str], cwd: str) -> str` – Reihenfolge laut A4
- `normalize_path(path: str, windows: bool = IS_WINDOWS) -> str` – absolut, `realpath` falls existent, `\`→`/`, ohne Trailing `/` (außer Wurzel `C:/` bzw. `/`), auf Windows `casefold()`
- `is_within(child: str, parent: str, windows: bool = IS_WINDOWS) -> bool` – Gleichheit oder Pfadgrenze
- `is_pid_alive(pid: int) -> bool` – Windows: `ctypes` `OpenProcess(0x1000, False, pid)`; Handle 0 → alive gdw. `GetLastError() == 5`; sonst `GetExitCodeProcess == 259`; Handle schließen. POSIX: `os.kill(pid, 0)`, `PermissionError` → True, `ProcessLookupError` → False. `pid <= 0` → False.
- `@dataclass(frozen=True) class Instance: port: int; project_name: str; project_path: str; pid: int; mtime: float`
- `load_instances(instances_dir: Path) -> list[Instance]` – ignoriert stillschweigend (stderr-Log) kaputtes JSON, fehlende/falsch typisierte Felder, `projectPath` null, Ports außerhalb `PORTS`, `*.tmp`
- `select_instance(instances: list[Instance], project_dir: str, *, alive: Callable[[int], bool] = is_pid_alive, exclude_ports: frozenset[int] = frozenset(), windows: bool = IS_WINDOWS) -> Instance | None` – Regel aus A3

- [ ] **Step 1: Failing Tests** (`class ResolutionTest(unittest.TestCase)`, Temp-Dirs via `tempfile.TemporaryDirectory`):

```python
def test_windows_normalization(self):
    self.assertEqual(normalize_path(r"C:\Users\Admin\Proj\\", windows=True), normalize_path("c:/users/admin/proj", windows=True))
def test_prefix_trap(self):
    self.assertFalse(is_within("C:/x/FooBar", "C:/x/Foo", windows=True))
    self.assertTrue(is_within("C:/x/Foo/src", "c:\\x\\foo", windows=True))
def test_posix_is_case_sensitive(self):
    self.assertFalse(is_within("/home/a/Proj", "/home/a/proj", windows=False))
def test_nearest_parent_wins(self):   # FileSearch vs FileSearch/SearchAndGroup
    a, b = inst(6789, "FileSearch", "C:/p/FileSearch"), inst(6790, "SearchAndGroup", "C:/p/FileSearch/SearchAndGroup")
    self.assertEqual(select_instance([a, b], "C:/p/FileSearch/SearchAndGroup/sub", alive=lambda p: True, windows=True), b)
def test_dead_pid_and_excluded_port_are_skipped(self):
    a = inst(6789, "X", "C:/p/X", pid=1); b = inst(6791, "X", "C:/p/X", pid=2, mtime=1.0)
    self.assertEqual(select_instance([a, b], "C:/p/X", alive=lambda p: p == 2, windows=True), b)
    self.assertIsNone(select_instance([b], "C:/p/X", alive=lambda p: True, exclude_ports=frozenset({6791}), windows=True))
def test_no_match_returns_none(self): ...   # anderes Projekt offen → None
def test_load_skips_garbage(self):
    # Dateien: gültig (6789), "not json", {"port":7000,...}, {"port":6790,"projectPath":null,...}, 6791.json.123.tmp
    self.assertEqual([i.port for i in load_instances(d)], [6789])
def test_is_pid_alive_does_not_kill(self):
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
    try:
        self.assertTrue(is_pid_alive(child.pid)); self.assertTrue(is_pid_alive(child.pid))
        self.assertIsNone(child.poll())           # still running after two checks
    finally:
        child.kill(); child.wait()
    self.assertFalse(is_pid_alive(child.pid))
def test_project_dir_precedence(self):
    env = {"INTELLIJ_MCP_PROJECT_DIR": "A", "CLAUDE_PROJECT_DIR": "B"}
    self.assertEqual(resolve_project_dir("CLI", env, "CWD"), "CLI")
    self.assertEqual(resolve_project_dir(None, env, "CWD"), "A")
    self.assertEqual(resolve_project_dir(None, {"CLAUDE_PROJECT_DIR": "B"}, "CWD"), "B")
    self.assertEqual(resolve_project_dir(None, {}, "CWD"), "CWD")
```
(`inst(...)` ist ein Test-Helper, der `Instance` mit Defaults `pid=1, mtime=0.0` baut.)

- [ ] **Step 2:** `python3 -m unittest discover -s proxy -v` → FAIL (`ImportError`).
- [ ] **Step 3:** Funktionen gemäß Interfaces implementieren; Modul-Docstring erklärt Zweck und Recordformat.
- [ ] **Step 4:** `python3 -m unittest discover -s proxy -v` → alle PASS.
- [ ] **Step 5: Commit** – `git add proxy/intellij_mcp_proxy.py proxy/test_intellij_mcp_proxy.py` → `feat(proxy): resolve the IDE window serving the session's project`

### Task 3: Proxy – Forwarding und stdio-Loop

**Files:**
- Modify: `proxy/intellij_mcp_proxy.py`
- Test: `proxy/test_intellij_mcp_proxy.py`

**Interfaces:**
- Consumes: Task 2 (`load_instances`, `select_instance`, `default_state_dir`, `resolve_project_dir`).
- Produces:
  - Fehlercodes `NO_WINDOW = -32001`, `BACKEND_ERROR = -32002`, `TOKEN_ERROR = -32003`
  - `no_window_message(project_dir: str, open_windows: list[Instance]) -> str`, exakter Text:
    `No IntelliJ window is open for '{project_dir}'. Open this project in IntelliJ IDEA (IntelliJ MCP Server plugin installed, project trusted); the proxy picks it up automatically. Open windows: {liste}` – `{liste}` = `"Name (Pfad) :Port"` kommagetrennt bzw. `none`.
  - `class Forwarder(state_dir: Path, project_dir: str, timeout: float = 120.0, alive=is_pid_alive)` mit `handle(message: dict) -> dict | None` (None ⇔ Notification) und `has_backend() -> bool`
  - `serve(stdin: BinaryIO, stdout: BinaryIO, forwarder: Forwarder, workers: int = 4, poll_interval: float = 2.0) -> None`
  - `main(argv: list[str] | None = None) -> int` mit `--project-dir`, `--state-dir`, `--timeout`; `if __name__ == "__main__": sys.exit(main())`

Verhalten (bestimmt die Tests): HTTP-POST via `urllib.request` an `http://127.0.0.1:{port}/mcp`, Header `Authorization: Bearer <token>`, `Content-Type: application/json`, `X-IntelliJ-Project: urllib.parse.quote(project_name, safe="")`. Retry-Regeln laut A6. Kein-Fenster-Verhalten laut A5. `initialize` mit Backend: Antwort weiterreichen, `result.capabilities.tools.listChanged = true` setzen. Notifications: weiterleiten (Fehler ignorieren), nie etwas ausgeben. `serve` liest `stdin` zeilenweise (Bytes), ungültiges JSON → Parse-Error `-32700` mit `id: null`; jede Nachricht in einem `ThreadPoolExecutor`; Schreiben auf `stdout` unter einem Lock als `json.dumps(obj, ensure_ascii=False).encode("utf-8") + b"\n"` + `flush()`. Der Poll-Thread läuft nur, solange zuletzt kein Backend gefunden wurde, und sendet beim Übergang „kein Backend → Backend“ genau einmal `{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}`. EOF auf stdin → Executor leeren, return.

- [ ] **Step 1: Failing Tests** – Fixture `FakeBackend`: `http.server.ThreadingHTTPServer` auf `127.0.0.1:0`, zeichnet Header/Bodies auf, Antwort pro Test konfigurierbar (Status, Body, Verzögerung). Da Ports aus `PORTS` stammen müssen, bekommen Tests einen Modul-Hook: `Forwarder` baut URLs über die überschreibbare Methode `_url(port: int) -> str`; Tests mappen den Record-Port (z. B. 6789) auf den echten Fake-Port. Records + Token in Temp-State-Dir, `alive=lambda pid: True`.

```python
def test_forwards_with_token_and_project_header(self):
    # Record 6789 "Mein Projekt" → Fake; tools/list
    resp = fwd.handle({"jsonrpc":"2.0","id":1,"method":"tools/list"})
    self.assertEqual(resp["result"], backend_result)
    self.assertEqual(backend.headers["Authorization"], "Bearer " + TOKEN)
    self.assertEqual(backend.headers["X-IntelliJ-Project"], "Mein%20Projekt")
def test_notification_produces_no_output(self):   # Backend antwortet {"jsonrpc":"2.0","id":null,"error":{...}}
    self.assertIsNone(fwd.handle({"jsonrpc":"2.0","method":"notifications/initialized"}))
def test_no_window(self):
    self.assertEqual(fwd.handle(init_msg)["result"]["capabilities"]["tools"]["listChanged"], True)
    self.assertIn("No IntelliJ window is open for", fwd.handle(init_msg)["result"]["instructions"])
    self.assertEqual(fwd.handle(tools_list)["result"], {"tools": []})
    self.assertEqual(fwd.handle(tools_call)["error"]["code"], -32001)
def test_initialize_with_backend_injects_list_changed(self): ...
def test_window_opened_later_is_used_without_restart(self):   # erst kein Record, dann Record schreiben → nächster Request geht durch
def test_refused_port_falls_back_to_other_live_record(self):  # Record 6790 (tote Fake-Adresse, jüngere mtime) + 6789 (Fake) → Antwort von 6789
def test_421_triggers_reresolve(self):    # 6789 antwortet 421 und wird danach durch Record-Umschreiben ersetzt → zweiter Versuch trifft neuen Port
def test_401_rereads_token_once(self):    # Fake akzeptiert nur NEW_TOKEN; Token-Datei vor dem Request auf NEW_TOKEN ändern → Erfolg; dauerhaft 401 → -32003
def test_missing_token_file(self):        # → -32003, Meldung nennt den Token-Pfad
def test_serve_parallel_and_utf8(self):
    # serve() mit BytesIO-Pipe: 8 tools/call-Zeilen, Fake verzögert 0.2 s und antwortet mit Text "ä€"
    # → 8 Ausgabezeilen, jede valides JSON, ids == {1..8}, keine b"\r\n", "ä€" decodiert identisch, Gesamtdauer < 8*0.2 s
def test_serve_parse_error(self):         # Zeile b"{oops\n" → {"id": null, "error": {"code": -32700, ...}}
def test_list_changed_sent_when_window_appears(self):  # poll_interval=0.05; erst kein Record, tools/list, dann Record → Notification-Zeile erscheint genau einmal
def test_cli_subprocess_roundtrip(self):
    # subprocess.run([sys.executable, "-I", PROXY, "--state-dir", d, "--project-dir", p], input=b'{"jsonrpc":"2.0","id":1,"method":"tools/list"}\n')
    # (kein Record) → returncode 0, stdout genau eine Zeile mit {"tools": []}, stderr enthält den Projektpfad
```

- [ ] **Step 2:** `python3 -m unittest discover -s proxy -v` → neue Tests FAIL.
- [ ] **Step 3:** Implementieren gemäß Interfaces/Verhalten. stdin/stdout immer über `.buffer` (in `main`), nie Textmodus.
- [ ] **Step 4:** `python3 -m unittest discover -s proxy -v` → alle PASS (lokal auf Windows; die Windows-Zweige von `is_pid_alive`/`normalize_path` laufen nur hier).
- [ ] **Step 5: Commit** – `git add proxy/intellij_mcp_proxy.py proxy/test_intellij_mcp_proxy.py` → `feat(proxy): forward MCP stdio traffic to the matching IDE window`

### Task 4: Plugin liefert und installiert den Proxy; CI und README

**Files:**
- Modify: `src/main/kotlin/com/example/mcpserver/auth/McpAuthStore.kt`
- Create: `src/main/kotlin/com/example/mcpserver/proxy/ProxyInstaller.kt`
- Modify: `src/main/kotlin/com/example/mcpserver/McpPluginService.kt` (`startServer`, nach Token)
- Modify: `build.gradle.kts` (`processResources`), `.github/workflows/ci.yml`, `README.md`, `src/main/resources/META-INF/plugin.xml` (`<description>` ohne „localhost:6789“)
- Test: `src/test/kotlin/com/example/mcpserver/proxy/ProxyInstallerTest.kt`

**Interfaces:**
- Produces: `McpAuthStore.BIN_DIR = "bin"`, `McpAuthStore.binDir: Path`; `McpAuthStore.writePrivateFile(file: Path, content: String)` (internal; atomar über Temp-Datei + Move, owner-only; `writeInstance` nutzt es); `class ProxyInstaller(store: McpAuthStore, resource: String = "/proxy/intellij_mcp_proxy.py")` mit `fun install(): Path` → `store.binDir/intellij_mcp_proxy.py`, schreibt nur bei abweichendem Inhalt, wirft `IllegalStateException`, wenn die Ressource fehlt. `ProxyInstaller.FILE_NAME = "intellij_mcp_proxy.py"`.

- [ ] **Step 1: Failing Tests** (`ProxyInstallerTest`, plain JUnit, Store in Temp-Dir):
  - `installs the bundled proxy` – Datei existiert, Inhalt == Classpath-Ressource, Inhalt beginnt mit `#!/usr/bin/env python3`.
  - `reinstall with identical content does not rewrite` – `lastModifiedTime` vor/nach zweitem `install()` gleich (vorher auf einen alten Zeitstempel setzen).
  - `outdated proxy is replaced` – Datei mit `"old"` vorbelegen → nach `install()` Ressourceninhalt.
  - `missing resource fails loudly` – `ProxyInstaller(store, "/nope.py").install()` wirft `IllegalStateException`.
- [ ] **Step 2:** `./gradlew test --tests '*ProxyInstallerTest*'` → FAIL.
- [ ] **Step 3:** Implementieren. `build.gradle.kts`: `tasks.processResources { from("proxy/intellij_mcp_proxy.py") { into("proxy") } }`. In `startServer` nach `loadOrCreateToken()`: `runCatching { ProxyInstaller(authStore).install() }.onFailure { LOG.warn("Could not install the MCP stdio proxy", it) }` – kein Abbruch des Serverstarts.
- [ ] **Step 4:** `./gradlew test buildPlugin` → PASS; `unzip -l build/distributions/intellij-mcp-server-*.zip` zeigt das Plugin-JAR, `unzip -l` des JARs enthält `proxy/intellij_mcp_proxy.py`.
- [ ] **Step 5: CI** – in `ci.yml` vor „Test and build plugin“: Step `Proxy tests` mit `run: python3 -m unittest discover -s proxy -v` (ubuntu-latest hat Python 3 vorinstalliert, keine neue Action).
- [ ] **Step 6: README** – Abschnitt „Client setup“ neu: empfohlener Weg = user-scope stdio-Proxy (`claude mcp add intellij -s user -- python3 -I <home>/.intellij-mcp-server/bin/intellij_mcp_proxy.py`), Erklärung Auflösung/Overrides (`--project-dir`, Env-Variablen), Hinweis, dass local/project-scope-Einträge namens `intellij` den user-scope überschatten; der bisherige HTTP-Weg bleibt als „Direktverbindung (ein Projekt)“ verkürzt erhalten.
- [ ] **Step 7: Commit** – `git add build.gradle.kts .github/workflows/ci.yml README.md src/main/resources/META-INF/plugin.xml src/main/kotlin/com/example/mcpserver/auth/McpAuthStore.kt src/main/kotlin/com/example/mcpserver/proxy/ProxyInstaller.kt src/main/kotlin/com/example/mcpserver/McpPluginService.kt src/test/kotlin/com/example/mcpserver/proxy/ProxyInstallerTest.kt` → `feat: bundle the stdio proxy and install it next to the token`

### Task 5: End-to-End-Smoke-Test mit echter IDE (ohne Konfigurationsänderung)

Kein Code; Verifikation, dass Teil A gegen echte Fenster funktioniert, bevor irgendeine Client-Config angefasst wird.

- [ ] **Step 1:** Plugin-ZIP aus Task 4 per „Install from Disk“ installieren, IDE neu starten, TaskWolf und FeelingsDiary öffnen. Prüfen: `~/.intellij-mcp-server/bin/intellij_mcp_proxy.py` existiert, `instances/` enthält 6789/6790.
- [ ] **Step 2:** Für beide Projekte (jeweils mit dem Projekt als cwd):
  `printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"project_structure","arguments":{}}}' | python3 -I ~/.intellij-mcp-server/bin/intellij_mcp_proxy.py`
  Erwartet: Antwort beschreibt jeweils das eigene Projekt (Projektname im Ergebnis), stderr nennt den gewählten Port.
- [ ] **Step 3:** In einem Unterordner eines Projekts (`src/`) wiederholen → gleiches Projekt. In einem Verzeichnis ohne offenes Fenster (z. B. `C:/Users/Admin`) → `-32001` mit Liste der offenen Fenster.
- [ ] **Step 4:** Fenster TaskWolf schließen, Request aus TaskWolf-cwd → `-32001`; wieder öffnen → nächster Request funktioniert ohne Proxy-Neustart.
- [ ] **Step 5:** Ergebnis (inkl. eventueller Abweichungen) Wolfgang berichten. Kein Commit.

### Task 6: Client-Konfiguration umstellen – **erst nach Wolfgangs ausdrücklichem OK**

**Files (außerhalb des Repos):** `~/.claude.json`; im Repo: `.mcp.json` (gemäß Entscheidungsfrage 2), Commit auf dem Feature-Branch.

- [ ] **Step 1: Backup** – `cp ~/.claude.json ~/.claude.json.bak-$(date +%Y%m%d-%H%M%S)`; Backup-Pfad notieren. Prüfen, dass keine anderen Claude-Code-Sessions gerade Konfiguration schreiben (Wolfgang fragen), da Claude Code die Datei selbst häufig neu schreibt – Änderungen deshalb nur über die `claude mcp`-CLI, nicht per Hand-Edit.
- [ ] **Step 2: Startbarkeit ohne Shell prüfen** (Entscheidungsfrage 4): `claude mcp add intellij-proxy-probe -s local -- python3 -I C:/Users/Admin/.intellij-mcp-server/bin/intellij_mcp_proxy.py` in FeelingsDiary, `claude mcp list` → `✓ Connected`; danach `claude mcp remove intellij-proxy-probe -s local`. Bei Fehlschlag absoluten Python-Pfad verwenden.
- [ ] **Step 3: User-scope** – `claude mcp remove intellij -s user` und `claude mcp add intellij -s user -- python3 -I C:/Users/Admin/.intellij-mcp-server/bin/intellij_mcp_proxy.py`.
- [ ] **Step 4: Local-scope-Schatten entfernen** – im Verzeichnis `C:/Users/Admin/IdeaProjects/FileSearch/SearchAndGroup`: `claude mcp remove intellij -s local`. Danach per Python-Read-only-Skript prüfen, dass in `~/.claude.json` kein `projects[*].mcpServers.intellij` mehr existiert und der user-scope-Eintrag `type: stdio` ist.
- [ ] **Step 5: Repo-`.mcp.json`** – laut Entscheidung löschen (`git rm .mcp.json`) oder ersetzen; Commit `chore: rely on the user-scope stdio proxy instead of a fixed port`.
- [ ] **Step 6: Verifikation** – neue Claude-Code-Sessions in TaskWolf, FeelingsDiary und SearchAndGroup (letzteres ohne offenes Fenster → Server verbunden, keine Tools, Hinweis in `instructions`): `/mcp` und ein `project_structure`-Call liefern jeweils das eigene Projekt. Rollback-Anleitung: Backup zurückkopieren.

---

## Teil B – Update-Pfad über GitHub

### Task 7: Plugin-ID und Vendor – **nur wenn Entscheidungsfrage 1 = ja**

**Files:** `src/main/resources/META-INF/plugin.xml`, `build.gradle.kts` (`group`, `version`), `README.md`, Test: `src/test/kotlin/com/example/mcpserver/PluginDescriptorTest.kt`

- [ ] **Step 1: Failing Test** – `plugin id and vendor are not placeholders`: liest `<id>`/`<vendor>` aus `META-INF/plugin.xml`, `assertEquals("io.github.wolftasks.intellij-mcp-server", id)`, `assertEquals("WolfTasks", vendor)`.
- [ ] **Step 2:** `./gradlew test --tests '*PluginDescriptorTest*'` → FAIL.
- [ ] **Step 3:** `<id>`/`<vendor>` ändern (Vendor mit `url="https://github.com/WolfTasks/intellij-mcp-server"`), `group = "io.github.wolftasks"`, `version = "1.2.0"`; in `pluginVerification.freeArgs` `ForbiddenPluginIdPrefix` aus `-mute` entfernen (nur `TemplateWordInPluginName` bleibt, falls weiterhin nötig). README: Abschnitt „Migration auf 1.2.0“ (alte Version deinstallieren, IDE neu starten, aus Repository installieren).
- [ ] **Step 4:** `./gradlew test buildPlugin verifyPlugin` → PASS, Verifier ohne ID-Warnung.
- [ ] **Step 5: Commit** – explizite Pfade → `build!: move the plugin id off the com.example placeholder`

### Task 8: `updatePlugins.xml` generieren und Release-Workflow

**Files:** `build.gradle.kts`, Create: `.github/workflows/release.yml`, `README.md`

**Interfaces:**
- Produces: Gradle-Task `generateUpdatePluginsXml` → `build/distributions/updatePlugins.xml`. Plugin-ID wird per Regex `<id>(.+?)</id>` aus `src/main/resources/META-INF/plugin.xml` gelesen (eine Quelle der Wahrheit), `since-build` aus derselben Konstante wie `patchPluginXml` (`val sinceBuild = "243"` herausziehen). Inhalt:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="{id}" url="https://github.com/WolfTasks/intellij-mcp-server/releases/download/v{version}/intellij-mcp-server-{version}.zip" version="{version}">
    <idea-version since-build="243"/>
    <name>IntelliJ MCP Server</name>
    <vendor>{vendor}</vendor>
  </plugin>
</plugins>
```

- [ ] **Step 1:** Task registrieren (Inputs: version, plugin.xml; Output-Datei deklariert, damit Up-to-date-Checks stimmen).
- [ ] **Step 2: Verifizieren** – `./gradlew buildPlugin generateUpdatePluginsXml` und
  `python3 -I -c "import xml.etree.ElementTree as E,sys;p=E.parse('build/distributions/updatePlugins.xml').find('plugin');print(p.get('id'),p.get('version'),p.get('url'))"` → ID, `1.2.0`, URL endet auf genau den Dateinamen in `build/distributions/*.zip`.
- [ ] **Step 3: `release.yml`** – `on: push: tags: ['v*']`, `permissions: contents: write`, ein Job auf `ubuntu-latest` mit denselben gepinnten Actions wie `ci.yml` (checkout, setup-java 21 temurin, setup-gradle). Steps:
  1. Tag-Check: `V=$(./gradlew -q properties --property version | sed -n 's/^version: //p'); test "v$V" = "$GITHUB_REF_NAME" || { echo "::error::Tag $GITHUB_REF_NAME != version $V"; exit 1; }`
  2. `python3 -m unittest discover -s proxy -v`
  3. `./gradlew test buildPlugin generateUpdatePluginsXml --console=plain`
  4. Konsistenzcheck: ZIP-Name aus `build/distributions/*.zip` == Dateiname in der XML-URL.
  5. `gh release create "$GITHUB_REF_NAME" build/distributions/*.zip build/distributions/updatePlugins.xml proxy/intellij_mcp_proxy.py --title "$GITHUB_REF_NAME" --generate-notes` mit `env: GH_TOKEN: ${{ github.token }}`. Kein `--prerelease` (sonst greift `latest/download` nicht).
- [ ] **Step 4:** Workflow mit `actionlint .github/workflows/release.yml` prüfen, falls installiert (sonst Review durch Lesen); der echte Test ist Task 9.
- [ ] **Step 5: README** – Abschnitt „Updates“: einmalig Settings → Plugins → ⚙ → Manage Plugin Repositories → URL aus Global Constraints eintragen; danach Updates wie bei Marketplace-Plugins. Release-Ablauf für Maintainer: Version in `build.gradle.kts` erhöhen → PR → Merge → Tag `vX.Y.Z` auf `master` pushen.
- [ ] **Step 6: Commit** – `git add build.gradle.kts .github/workflows/release.yml README.md` → `ci: publish tagged releases with an IntelliJ custom repository descriptor`

### Task 9: Erstes Release und einmalige Umstellung in der IDE (mit Wolfgang)

Erst nach Merge des Feature-Branches per PR; Tags pusht Wolfgang (oder auf seine ausdrückliche Anweisung).

- [ ] **Step 1:** Tag `v1.2.0` auf dem Merge-Commit von `master` → Release-Workflow grün; Release enthält ZIP, `updatePlugins.xml`, `intellij_mcp_proxy.py`.
- [ ] **Step 2:** `curl -sL https://github.com/WolfTasks/intellij-mcp-server/releases/latest/download/updatePlugins.xml` liefert die XML mit Version 1.2.0; `curl -sIL <zip-url>` endet mit `200`.
- [ ] **Step 3 (Wolfgang in der IDE):** Repository-URL eintragen; bei ID-Wechsel: altes Plugin deinstallieren → Neustart → im Marketplace-Tab nach „IntelliJ MCP Server“ suchen (Custom-Repos erscheinen dort) → installieren → Neustart. Ohne ID-Wechsel: Update wird direkt angeboten. Die Meldung „updateable plugins without update source“ verschwindet.
- [ ] **Step 4:** Prüfen: `bin/intellij_mcp_proxy.py` wurde von der neuen Version geschrieben (Inhalt == Release-Asset), Claude-Sessions funktionieren weiter (Task-6-Verifikation wiederholen).
- [ ] **Step 5 (optional, später):** Beim nächsten Patch-Release (1.2.1) bestätigen, dass IntelliJ das Update von selbst anbietet.

---

## Reihenfolge und Abhängigkeiten

Task 1 → 2 → 3 → 4 → 5 sind sequenziell (gemeinsame Datei bzw. Interfaces). Task 6 hängt an Task 5 + Wolfgangs OK. Task 7 und 8 sind unabhängig von Teil A und können parallel zu Task 5/6 laufen; Task 9 braucht 4, 7 (falls gewählt) und 8 auf `master`. Empfohlener PR-Schnitt: PR 1 = Tasks 1–4 (+ `.mcp.json` aus Task 6), PR 2 = Tasks 7–8.
