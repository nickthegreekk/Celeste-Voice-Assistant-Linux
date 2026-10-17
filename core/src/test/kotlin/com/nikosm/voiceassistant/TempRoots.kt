package com.nikosm.voiceassistant

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Collections

/**
 * Temporary trees the test fixtures create, removed when the test JVM exits.
 *
 * **`File.deleteOnExit()` is not enough, and the reason is worth keeping**: it removes a directory
 * only when it is *empty*, and these are not. A fake `AppEnv`'s root gains `state/fs-tool-audit.jsonl`
 * the first time a test exercises a tool, because the audit writer creates its directory — so the
 * delete is silently refused and the tree stays. Measured on 2026-10-17: 561 of them had accumulated
 * in `/tmp`, one per service a test had built, plus 505 workspaces that held real files.
 *
 * A single hook for the whole process, rather than one per root: the roots are few and the hook runs
 * once. `reverseOrder` walks the deepest paths first, which is the order a tree is deleted in.
 */
internal object TempRoots {

    private val roots: MutableList<Path> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var hooked = false

    /**
     * The prefixes this JVM owns. `celeste-fresh-start` is here for the trees the *old* fixture made,
     * before this class existed — they are still in `/tmp` on any machine that ran the suite.
     */
    private val ownPrefixes = listOf("celeste-test", "celeste-fs-turn", "celeste-fresh-start")

    /** A new temporary root that will not outlive the test run. */
    fun newRoot(prefix: String): Path {
        val root = Files.createTempDirectory(prefix)
        roots.add(root)
        synchronized(this) {
            if (!hooked) {
                hooked = true
                // **The sweep is the reliable half.** A shutdown hook fires on a graceful exit, and
                // Gradle's test worker is not guaranteed one — measured on 2026-10-17: the hook was
                // registered, the worker exited, the trees stayed. So the first fixture of a run
                // deletes what a *previous* run left behind, which is bounded litter rather than
                // growth. Safe because this repository forbids parallel builds (AGENTS.md §6), so
                // there is never a second test JVM to collide with.
                sweepPreviousRuns()
                Runtime.getRuntime().addShutdownHook(Thread({ deleteAll() }, "temp-roots-cleanup"))
            }
        }
        return root
    }

    private fun sweepPreviousRuns() {
        runCatching {
            val tmp = Path.of(System.getProperty("java.io.tmpdir"))
            if (!Files.isDirectory(tmp)) return@runCatching
            Files.list(tmp).use { entries ->
                entries.filter { path ->
                    val name = path.fileName.toString()
                    Files.isDirectory(path) && ownPrefixes.any { name.startsWith(it) }
                }.forEach { stale -> runCatching { deleteTree(stale) } }
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    /** Best-effort: a tree that cannot be deleted is a littered `/tmp`, never a failed test. */
    private fun deleteAll() {
        val snapshot = synchronized(roots) { roots.toList() }
        snapshot.forEach { root -> runCatching { deleteTree(root) } }
    }
}
