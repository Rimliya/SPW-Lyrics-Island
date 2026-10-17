package dev.gaboron.spwlyrics.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.Path

/**
 * Resolves the single writable data root for the plugin.
 *
 * Windows builds prefer `D:\SPW Lyrics` so the generated lyrics cache does not grow the system
 * drive. `SPW_LYRICS_HOME` overrides the choice, and the previous `%LOCALAPPDATA%` folder is
 * adopted once, which keeps delay settings and already resolved lyrics.
 */
internal object DataDirectory {
    private const val FOLDER = "SPW Lyrics"
    private const val OVERRIDE_ENV = "SPW_LYRICS_HOME"
    private const val PREFERRED_DRIVE = "D:\\"

    val root: Path by lazy { resolveRoot() }

    private fun resolveRoot(): Path {
        val legacy = legacyRoot()
        val override = System.getenv(OVERRIDE_ENV)?.trim()?.takeIf(String::isNotEmpty)
        if (override != null) {
            val directory = Path(override)
            if (!isWritable(directory)) return legacy
            runCatching { Files.createDirectories(directory) }
            return directory
        }
        val preferred = preferredRoot()
        if (preferred != null && preferred != legacy && isWritable(preferred)) {
            adopt(legacy, preferred)
            runCatching { Files.createDirectories(preferred) }
            return preferred
        }
        return legacy
    }

    private fun preferredRoot(): Path? {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return null
        return Path(PREFERRED_DRIVE).resolve(FOLDER)
    }

    private fun legacyRoot(): Path {
        val base = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(::Path)
            ?: Path(System.getProperty("user.home"))
        return base.resolve(FOLDER)
    }

    /** Probes the parent so the target folder stays absent until the old data has been adopted. */
    private fun isWritable(directory: Path): Boolean = runCatching {
        val probeDirectory = directory.parent ?: directory
        Files.createDirectories(probeDirectory)
        val probe = Files.createTempFile(probeDirectory, ".write-test", ".tmp")
        Files.deleteIfExists(probe)
        true
    }.getOrDefault(false)

    /** Moves the old LOCALAPPDATA folder once; any failure keeps the original copy untouched. */
    private fun adopt(from: Path, to: Path) {
        if (!Files.isDirectory(from) || Files.exists(to)) return
        runCatching {
            runCatching { Files.move(from, to) }.getOrElse {
                val staging = to.resolveSibling("${to.fileName}.migrating")
                deleteTree(staging)
                copyTree(from, staging)
                Files.move(staging, to)
                if (treeSize(to) == treeSize(from)) deleteTree(from)
            }
        }
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target)
                else Files.copy(source, target)
            }
        }
    }

    private fun treeSize(directory: Path): Long = Files.walk(directory).use { paths ->
        paths.filter(Files::isRegularFile).mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
    }

    private fun deleteTree(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}