package com.example.mcpserver.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumSet
import com.intellij.openapi.diagnostic.Logger

/**
 * Per-user state of the MCP server, kept outside any project:
 *
 *  - `token`: the shared secret clients must send as `Authorization: Bearer <token>`.
 *    Generated once (256 bit, SecureRandom) and reused across IDE restarts and projects.
 *  - `instances/<port>.json`: discovery records mapping each running server port to its project.
 *
 * Directory and files are restricted to the current user: 0700/0600 on POSIX, an owner-only ACL on
 * Windows. Note that this does not protect against other processes of the *same* user — those can
 * read the token, just as they can read the project files directly.
 */
class McpAuthStore(val dir: Path = defaultDir()) {

    companion object {
        const val TOKEN_FILE = "token"
        const val INSTANCES_DIR = "instances"
        private const val TOKEN_BYTES = 32
        private const val MIN_TOKEN_LENGTH = 32
        private val LOG = Logger.getInstance(McpAuthStore::class.java)

        /** System property overriding the state directory (used by tests to stay away from the real one). */
        const val HOME_PROPERTY = "intellij.mcp.server.home"

        fun defaultDir(): Path {
            val override = System.getProperty(HOME_PROPERTY)
            return if (!override.isNullOrBlank()) Path.of(override)
            else Path.of(System.getProperty("user.home"), ".intellij-mcp-server")
        }

        fun generateToken(): String {
            val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    val tokenFile: Path get() = dir.resolve(TOKEN_FILE)
    val instancesDir: Path get() = dir.resolve(INSTANCES_DIR)

    /** Returns the existing token, or generates and persists a new one. Safe against concurrent callers. */
    @Synchronized
    fun loadOrCreateToken(): String {
        ensurePrivateDir(dir)
        readToken()?.let { restrictToOwner(tokenFile, directory = false); return it }

        val token = generateToken()
        val tmp = createPrivateFile(dir.resolve("$TOKEN_FILE.${ProcessHandle.current().pid()}.tmp"))
        try {
            Files.writeString(tmp, token + "\n")
            if (Files.exists(tokenFile)) {
                // Present but unusable (empty/truncated): replace it.
                Files.move(tmp, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } else {
                try {
                    // Plain rename without REPLACE_EXISTING: fails if the file appeared meanwhile.
                    // (ATOMIC_MOVE would silently replace on Windows.)
                    Files.move(tmp, tokenFile)
                } catch (e: FileAlreadyExistsException) {
                    // Another IDE process won the race — use its token.
                    readToken()?.let { return it }
                    throw e
                }
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
        return token
    }

    private fun readToken(): String? {
        if (!Files.isRegularFile(tokenFile)) return null
        return Files.readString(tokenFile).trim().takeIf { it.length >= MIN_TOKEN_LENGTH }
    }

    /** Writes `instances/<port>.json` so a client (or the user) can see which port serves which project. */
    fun writeInstance(port: Int, json: String): Path {
        ensurePrivateDir(dir)
        ensurePrivateDir(instancesDir)
        val file = instancesDir.resolve("$port.json")
        val tmp = createPrivateFile(instancesDir.resolve("$port.json.${ProcessHandle.current().pid()}.tmp"))
        try {
            Files.writeString(tmp, json)
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return file
    }

    /**
     * Whether `instances/<port>.json` exists and names a running process — i.e. the port is most
     * likely held by another project's MCP server rather than by some foreign program.
     */
    fun isLiveInstance(port: Int): Boolean {
        val file = instancesDir.resolve("$port.json")
        if (!Files.isRegularFile(file)) return false
        val pid = runCatching {
            Json.parseToJsonElement(Files.readString(file)).jsonObject["pid"]?.jsonPrimitive?.longOrNull
        }.getOrNull() ?: return false
        return runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(false)
    }

    fun removeInstance(port: Int) {
        Files.deleteIfExists(instancesDir.resolve("$port.json"))
    }

    private fun ensurePrivateDir(path: Path) {
        if (!Files.isDirectory(path)) {
            if (supportsPosix(path.parent ?: path)) {
                Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            } else {
                Files.createDirectories(path)
            }
        }
        restrictToOwner(path, directory = true)
    }

    private fun createPrivateFile(path: Path): Path {
        Files.deleteIfExists(path)
        val created = if (supportsPosix(path.parent)) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(path)
        }
        restrictToOwner(created, directory = false)
        return created
    }

    /**
     * The account this process runs as. Not the file owner: in an elevated process the owner of new
     * files is BUILTIN\Administrators. Domain-qualified first, so a local account with the same name
     * as the domain account cannot be picked by mistake.
     */
    private fun currentUser(path: Path): UserPrincipal? {
        val lookup = path.fileSystem.userPrincipalLookupService
        val name = System.getProperty("user.name") ?: return null
        val candidates = listOfNotNull(System.getenv("USERDOMAIN")?.takeIf { it.isNotBlank() }?.let { "$it\\$name" }, name)
        for (candidate in candidates) {
            val principal = runCatching { lookup.lookupPrincipalByName(candidate) }.getOrNull()
            if (principal != null && principal !is GroupPrincipal) return principal
        }
        return null
    }

    private fun supportsPosix(path: Path): Boolean =
        Files.getFileStore(existingAncestor(path)).supportsFileAttributeView(PosixFileAttributeView::class.java)

    private fun existingAncestor(path: Path): Path {
        var p: Path? = path.toAbsolutePath()
        while (p != null && !Files.exists(p)) p = p.parent
        return p ?: path.toAbsolutePath().root
    }

    /**
     * 0700/0600 on POSIX; on ACL file systems (Windows) a single ALLOW entry for the current user and
     * nothing else (no inherited SYSTEM/Administrators/Users entries). On directories the entry is
     * inheritable, so files created inside start out owner-only as well.
     */
    private fun restrictToOwner(path: Path, directory: Boolean) {
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.let { view ->
            view.setPermissions(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            return
        }
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java) ?: return
        try {
            val entry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(currentUser(path) ?: acl.owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .apply { if (directory) setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) }
                .build()
            if (acl.acl != listOf(entry)) acl.acl = listOf(entry)
        } catch (e: IOException) {
            // Best effort: some file systems expose ACLs read-only. The home directory is private anyway.
            LOG.warn("Could not restrict ACL of $path to its owner", e)
        } catch (e: UnsupportedOperationException) {
            LOG.warn("Could not restrict ACL of $path to its owner", e)
        }
    }
}
