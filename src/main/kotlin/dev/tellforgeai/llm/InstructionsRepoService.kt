package dev.tellforgeai.llm

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import dev.tellforgeai.settings.InstructionsRepoState
import dev.tellforgeai.settings.RepoSpec
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Clones and caches the configured instructions repo (see
 * [InstructionsRepoState]) in the plugin temp directory and keeps the merged
 * instruction markdown in memory so building a system prompt never touches
 * the filesystem or spawns a git process.
 *
 * Deliberately simple: a shallow clone removed and re-created when stale
 * (TTL), `fetch` + `reset --hard` on refresh of an existing clone, and a
 * last-known-good in-memory copy served when git fails (offline etc.).
 * All git work runs on the caller's background thread; nothing here blocks
 * the EDT and nothing throws into the chat path except the explicit
 * [commitAndPush] used by the tellforge_instructions_write tool.
 */
@Service(Service.Level.PROJECT)
class InstructionsRepoService(private val project: Project) {

    private val LOG = Logger.getInstance(InstructionsRepoService::class.java)

    /** TTL for the on-disk clone; a fresh shallow clone costs ~1s. */
    private val cloneTtlMs = 10 * 60 * 1000L

    /** Merged instructions text + when it was read from disk. */
    /**
     * @param note a non-fatal informational message (e.g. "seeded a default
     *   INSTRUCTIONS.md and pushed it", "branch did not exist — created
     *   locally"). A successful sync can carry a note; [error] stays empty.
     */
    data class Snapshot(
        val text: String,
        val fileCount: Int,
        val syncedAt: Long,
        val fromCache: Boolean,
        val error: String,
        val note: String = "",
    )

    private val snapshot = AtomicReference(Snapshot("", 0, 0L, false, "not synced yet"))
    private var lastRefreshAttemptAt = 0L

    /**
     * Set by [seedDefaultInstructions] when a default INSTRUCTIONS.md was
     * auto-written this sync — surfaced once in the Test button result, then
     * cleared on the next successful read so it isn't repeated forever.
     */
    @Volatile private var lastSeedNote: String? = null

    /** True while a sync/clone is running — second Test clicks must not stack hung threads. */
    private val syncing = AtomicBoolean(false)

    /** True if a sync is currently running (for the dialog's Test button). */
    fun isSyncing(): Boolean = syncing.get()

    // ------------------------------------------------------------------
    // Reading (hot path — memory only)
    // ------------------------------------------------------------------

    /** The merged instructions for the system prompt (memory read, never git). */
    fun instructions(): String = snapshot.get().text

    /** One-line status; hides file-count/repo detail from the tool surface. */
    fun toolStatus(): String {
        val s = snapshot.get()
        return when {
            s.error.isNotEmpty() && s.text.isEmpty() -> "error: ${s.error}"
            s.fromCache -> "cached (offline)"
            else -> "saved ${age(s.syncedAt)}"
        }
    }

    /** One-line status for the UI, e.g. "synced 2 min ago (3 files)". */
    fun status(): String {
        val s = snapshot.get()
        return when {
            s.error.isNotEmpty() && s.text.isEmpty() -> "error: ${s.error}"
            s.fromCache -> "cached (offline)"
            else -> "synced ${age(s.syncedAt)} (${s.fileCount} files)"
        }
    }

    private fun age(at: Long): String {
        val mins = (System.currentTimeMillis() - at) / 60000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            else -> "${mins / 60}h ago"
        }
    }

    // ------------------------------------------------------------------
    // Refresh (background thread)
    // ------------------------------------------------------------------

    /**
     * Ensures a clone exists (shallow), fetches/reset when stale, reloads the
     * in-memory snapshot. Cheap-ish and idempotent; throttled to once per
     * minute so concurrent prompt builds don't stampede.
     */
    fun ensureFresh() {
        val spec = state().spec() ?: return
        val now = System.currentTimeMillis()
        if (now - lastRefreshAttemptAt < 60_000) return
        lastRefreshAttemptAt = now
        try {
            val dir = ensureClone(spec, branch())
            if (dir != null) reloadSnapshot(dir, spec)
        } catch (e: Exception) {
            LOG.warn("instructions repo refresh failed", e)
            markError(friendly(e))
        }
    }

    /** Force clone/refresh + reload; equivalent to syncNowFor with saved state. */
    fun syncNow(): Snapshot {
        val spec = state().spec() ?: return Snapshot("", 0, 0L, false, "repo URL not configured")
        return syncNowFor(spec, branch())
    }

    /**
     * Force sync with explicit spec/branch (used to test unsaved dialog
     * fields). On a missing branch reports the remote's default branch in the
     * error so the user can correct the field instead of guessing.
     *
     * [onStage] receives short progress notes ("cloning…", "fetching…") from
     * the background thread — the dialog forwards them to the UI so the user
     * can see how far the sync got when something fails.
     */
    fun syncNowFor(spec: RepoSpec, branchName: String, onStage: (String) -> Unit = {}): Snapshot {
        // Never stack a second clone behind one that may be hanging; report
        // immediately instead so the dialog doesn't pile up stuck threads.
        if (!syncing.compareAndSet(false, true)) {
            return Snapshot(snapshot.get().text, snapshot.get().fileCount,
                snapshot.get().syncedAt, true, "sync already in progress")
        }
        try {
            return doSyncNowFor(spec, branchName, onStage)
        } finally {
            syncing.set(false)
        }
    }

    private fun doSyncNowFor(spec: RepoSpec, branchName: String, onStage: (String) -> Unit): Snapshot {
        InstructionsSyncLog.mark("sync started (url=${spec.cloneUrl}, branch=$branchName)")
        onStage("resolving repo URL")
        val dir = try {
            ensureClone(spec, branchName, onStage) ?: return snapshot.get()
        } catch (e: MissingBranchException) {
            // Branch didn't exist on the remote: ensureClone already cloned
            // the remote default branch and created the branch locally on top
            // of it (unpushed). Load from that clone and report what happened.
            onStage("reading markdown files")
            val snap = reloadSnapshot(cloneDir(spec, branchName), spec)
            val note = if (snap.error.isEmpty()) {
                "branch '$branchName' did not exist — created locally from remote default " +
                    "'${e.defaultBranch}' (it will be pushed on first instructions_write)"
            } else snap.error
            if (snap.error.isNotEmpty()) return snap
            return Snapshot(snap.text, snap.fileCount, snap.syncedAt, false, "", note)
        } catch (e: Exception) {
            LOG.warn("instructions repo sync failed", e)
            InstructionsSyncLog.error("sync failed", e)
            markError(friendly(e))
            return snapshot.get().copy(error = friendly(e))
        }
        onStage("reading markdown files")
        val snap = reloadSnapshot(dir, spec)
        if (snap.error.isNotEmpty()) throw IllegalStateException(snap.error)
        onStage(if (lastSeedNote != null) "seeded default INSTRUCTIONS.md" else "done")
        val note = lastSeedNote
        lastSeedNote = null
        if (note != null) InstructionsSyncLog.mark(note)
        InstructionsSyncLog.info("sync finished: ${snap.fileCount} md files")
        return if (note != null) snap.copy(note = note) else snap
    }

    /**
     * Returns a ready clone directory, cloning (shallow) or fetching as
     * needed. Clones the remote's default branch when [branchName] was not
     * found — surface friendly([MissingBranchException]).
     */
    private fun ensureClone(spec: RepoSpec, branchName: String, onStage: (String) -> Unit = {}): File? {
        val dir = cloneDir(spec, branchName)
        if (!File(dir, ".git").isDirectory) {
            LOG.info("instructions repo: cloning ${spec.cloneUrl} (branch $branchName) into $dir")
            FileUtil.delete(dir)
            dir.parentFile.mkdirs()
            onStage("cloning (no local copy yet)")
            try {
                gitTimed(dir.parentFile, 120_000L, "clone", "--depth", "1", "-b", branchName, "--single-branch", spec.cloneUrl, dir.name)
            } catch (e: Exception) {
                if (isMissingBranch(e, branchName)) {
                    // Two distinct causes behind a missing remote ref:
                    //  - the remote has at least one branch (refs) → clone the
                    //    default branch and create the requested one on top;
                    //  - the remote is completely empty (no refs at all) →
                    //    nothing to clone; bootstrap the branch from scratch.
                    if (remoteHasRefs(spec.cloneUrl)) {
                        onStage("branch '$branchName' missing — cloning default branch")
                        gitTimed(dir.parentFile, 120_000L, "clone", "--depth", "1", "--single-branch", spec.cloneUrl, dir.name)
                        git(dir, "checkout", "-b", branchName)
                        throw MissingBranchException(remoteDefaultBranch(spec.cloneUrl))
                    }
                    onStage("repo is empty — creating branch '$branchName' and committing bootstrap")
                    bootstrapEmptyRepo(dir, spec, branchName, onStage)?.let { throw IllegalStateException(it) }
                    onStage("branch created and pushed — reading markdown files")
                    return dir
                }
                LOG.info("instructions repo: clone failed: ${e.message}")
                throw e
            }
        } else if (System.currentTimeMillis() - File(dir, ".git").lastModified() > cloneTtlMs) {
            LOG.info("instructions repo: clone stale — fetching ${spec.cloneUrl} (branch $branchName)")
            onStage("fetching updates (stale clone)")
            try {
                fetchReset(spec, dir, branchName)
            } catch (e: MissingBranchException) {
                // The remote lost the branch (or never had it) while a stale
                // local clone lingers — e.g. an empty repo that a previous,
                // buggy build cached. Drop the stale dir and reuse the clone
                // path, which knows how to bootstrap an empty remote. The
                // clone branch either succeeds (bootstrapped) or throws, so
                // this recurses at most once.
                LOG.info("instructions repo: branch '$branchName' missing on remote — re-cloning to bootstrap if empty")
                InstructionsSyncLog.warn("branch '$branchName' missing on remote during fetch — deleting stale clone and retrying")
                FileUtil.delete(dir)
                return ensureClone(spec, branchName, onStage)
            }
        } else {
            LOG.info("instructions repo: using fresh local clone at $dir")
            onStage("using fresh local clone")
        }
        return dir
    }

    private class MissingBranchException(val defaultBranch: String?) : RuntimeException()

    private fun isMissingBranch(e: Exception, branchName: String): Boolean {
        if (e.message?.contains("timed out") == true) return false // clone timeout, not a wrong branch
        val msg = (e.message ?: "").lowercase()
        return listOf(
            "couldn't find remote ref",   // git prints the contraction
            "could not find remote ref",
            "find remote ref",
            "remote origin",
            "not found",
            "does not exist",
            "not a valid ref",
        ).any { msg.contains(it) } && msg.contains(branchName.lowercase())
    }

    /**
     * True when the remote exposes no refs at all — an empty, never-pushed
     * repository. Used to distinguish "branch missing, repo has other
     * branches" from "repo completely empty". Best effort; network errors
     * return false so the original exception surfaces instead.
     */
    private fun remoteHasRefs(cloneUrl: String): Boolean = try {
        val pb = ProcessBuilder(listOf("git", "ls-remote", cloneUrl))
            .redirectErrorStream(true)
        applyNoPromptEnv(pb)
        val p = pb.start()
        val out = readAll(p, 15_000L)
        // Any printed ref line ("hash<TAB>ref") means the remote isn't empty.
        // Empty repo → empty output. Null means probe failed → assume refs.
        out != null && out.contains("\t")
    } catch (_: Exception) {
        true // can't tell; treat as having refs so the original error surfaces
    }

    /**
     * Bootstraps a completely empty remote: re-initializes [dir] as a fresh
     * repo on [branchName], makes an empty root commit and pushes it, so the
     * configured branch exists on the remote and later syncs behave normally.
     * Requires the write opt-in; without it, leaves the local repo ready and
     * lets the caller proceed with an explanatory note handed back through
     * the returned message (surfaced in the snapshot error).
     */
    private fun bootstrapEmptyRepo(dir: File, spec: RepoSpec, branchName: String, onStage: (String) -> Unit = {}): String? {
        FileUtil.delete(dir)
        dir.mkdirs()
        onStage("git init (branch '$branchName')")
        git(dir, "init", "-b", branchName)
        git(dir, "remote", "add", "origin", spec.cloneUrl)
        // Seed a starter INSTRUCTIONS.md so the branch is immediately usable —
        // a completely empty tree would just reproduce the confusing
        // "no .md files" error on the very next sync.
        onStage("writing starter INSTRUCTIONS.md")
        File(dir, CANONICAL_FILE).writeText(startInstructionsFile(), StandardCharsets.UTF_8)
        git(dir, "add", CANONICAL_FILE)
        onStage("committing bootstrap")
        git(dir, "commit", "-m", "Bootstrap instructions repo branch '$branchName' with starter INSTRUCTIONS.md")
        return try {
            onStage("pushing branch to remote")
            git(dir, "push", "-u", "origin", branchName)
            InstructionsSyncLog.info("bootstrapped empty remote: pushed branch '$branchName' to ${spec.cloneUrl}")
            null
        } catch (e: Exception) {
            // Push failed (e.g. write opt-in off, or unverifiable identity).
            // The local repo still works — the branch will be pushed by the
            // first successful commitAndPush if/when allowed.
            LOG.warn("instructions repo: bootstrap push failed (branch will be created locally only)", e)
            InstructionsSyncLog.warn("bootstrap push failed; local branch '$branchName' created only: ${e.message}")
            "repo was empty — created branch '$branchName' locally with a starter $CANONICAL_FILE, " +
                "pushing it failed (${(e.message ?: "").take(200)}) and will be retried on first instructions_write"
        }
    }

    private fun remoteDefaultBranch(cloneUrl: String) = gitSymref(cloneUrl)

    /** `git ls-remote --symref <url> HEAD` in an unrestricted working dir; best effort. */
    private fun gitSymref(cloneUrl: String): String = try {
        val pb = ProcessBuilder(listOf("git", "ls-remote", "--symref", cloneUrl, "HEAD"))
            .redirectErrorStream(true)
        applyNoPromptEnv(pb)
        val p = pb.start()
        val out = readAll(p, 30_000L) ?: return "main"
        out.lineSequence().firstOrNull { it.startsWith("ref:") }
            ?.substringAfterLast("refs/heads/")?.trim() ?: "main"
    } catch (_: Exception) {
        "main"
    }

    private fun fetchReset(spec: RepoSpec, dir: File, branchName: String) {
        try {
            git(dir, "fetch", "--depth", "1", "origin", branchName)
        } catch (e: Exception) {
            // A stale local clone pointing at a branch the remote no longer
            // has (or an empty repo a previous build cached) otherwise dies
            // here forever — surface it as MissingBranchException so
            // ensureClone can drop the clone and bootstrap if needed.
            if (isMissingBranch(e, branchName)) throw MissingBranchException(null)
            throw e
        }
        git(dir, "reset", "--hard", "FETCH_HEAD")
        git(dir, "clean", "-fd")
    }

    private fun reloadSnapshot(dir: File, spec: RepoSpec): Snapshot {
        val root = if (spec.subpath.isNotEmpty()) File(dir, spec.subpath) else dir
        if (!root.isDirectory) {
            val err = "configured subdirectory '${spec.subpath}' does not exist in the instructions repo " +
                "(repo contains: ${topLevelListing(dir)})"
            snapshot.set(Snapshot(snapshot.get().text, snapshot.get().fileCount, snapshot.get().syncedAt, true, err))
            return snapshot.get()
        }
        var files = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", true) }
            .sortedBy { it.path }
            .take(20)
            .toList()
        if (files.isEmpty() && seedDefaultInstructions(dir, spec, root)) {
            files = root.walkTopDown()
                .filter { it.isFile && it.extension.equals("md", true) }
                .sortedBy { it.path }
                .take(20)
                .toList()
        }
        if (files.isEmpty()) {
            val err = "no .md files found under '${spec.subpath.ifEmpty { "/" }}' in the instructions repo " +
                "(repo contains: ${topLevelListing(dir)})"
            snapshot.set(Snapshot(snapshot.get().text, snapshot.get().fileCount, snapshot.get().syncedAt, true, err))
            return snapshot.get()
        }
        // With a single file (the normal case) the text is returned without a
        // per-file header, so the model-facing instructions never reveal the
        // repo/filename layout. Multi-file repos keep the headers for context.
        val text = if (files.size == 1) {
            files.single().readText(StandardCharsets.UTF_8).trimEnd()
        } else {
            files.joinToString("\n\n") { f ->
                "### ${f.relativeTo(dir).invariantSeparatorsPath}\n" +
                    f.readText(StandardCharsets.UTF_8).trimEnd()
            }
        }
        val snap = Snapshot(text, files.size, System.currentTimeMillis(), false, "")
        snapshot.set(snap)
        return snap
    }

    /**
     * When the checked-out tree contains no markdown at all (e.g. a branch was
     * pushed empty earlier, so bootstrap never ran), seed a default
     * INSTRUCTIONS.md, commit it and — when the write opt-in is on — push it.
     * Returns true when a default file was created and (re)loaded.
     */
    private fun seedDefaultInstructions(dir: File, spec: RepoSpec, root: File): Boolean {
        val file = File(root, CANONICAL_FILE)
        if (!root.isDirectory) return false
        try {
            file.writeText(startInstructionsFile(), StandardCharsets.UTF_8)
            val repoRel = canonicalPath(spec.subpath)
            git(dir, "add", "--", repoRel)
            git(dir, "-c", "user.name=TellForgeAI", "-c", "user.email=tellforgeai@localhost",
                "commit", "-m", "Seed default $CANONICAL_FILE (repo had no instructions)")
            try {
                git(dir, "push", "origin", "HEAD:${branch()}")
                lastSeedNote = "repo had no instructions — seeded a default $CANONICAL_FILE and pushed it"
            } catch (e: Exception) {
                // Committing worked, pushing failed (write opt-in off? network?).
                // The local tree is still usable — note it and carry on.
                LOG.warn("instructions seed push failed (kept local commit)", e)
                InstructionsSyncLog.warn("seed push failed; local commit only: ${e.message}")
                lastSeedNote = "repo had no instructions — seeded a default $CANONICAL_FILE (committed locally; " +
                    "push failed: ${(e.message ?: "").take(120)})"
            }
            return true
        } catch (e: Exception) {
            // Seeding is best-effort; report the empty-repo error as before.
            LOG.warn("instructions seed failed", e)
            InstructionsSyncLog.warn("seeding default instructions failed: ${e.message}")
            return false
        }
    }

    /**
     * One-line, self-diagnosing listing of the clone root for error messages:
     * "README.md, docs/, src/ (3 files)". Empty → "(repo is empty)".
     */
    private fun topLevelListing(dir: File): String {
        if (!dir.isDirectory) return "(no clone)"
        val entries = dir.listFiles()
            ?.filter { it.name != ".git" }
            ?.sortedBy { it.name }
            ?.take(12)
            ?.map { if (it.isDirectory) "${it.name}/" else it.name }
            ?: emptyList()
        if (entries.isEmpty()) return "(repo is empty)"
        val more = (dir.listFiles()?.size ?: 0) - 12
        return entries.joinToString(", ") + (if (more > 0) " … +$more more" else "")
    }

    private fun markError(msg: String) {
        val s = snapshot.get()
        snapshot.set(Snapshot(s.text, s.fileCount, s.syncedAt, s.text.isNotEmpty(), msg))
    }

    /** Normalizes raw git errors into something a user can act on. */
    private fun friendly(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("timed out", true) ->
                "git timed out — the remote took too long to respond (network stall or firewalled host): $msg"
            msg.contains("terminal prompts disabled", true) || msg.contains("could not read Username", true) ||
                msg.contains("could not read Password", true) || msg.contains("authentication", true) ||
                msg.contains("Permission denied", true) ->
                "git authentication failed — credentials for this repo are not available to the IDE " +
                    "(a terminal git would have prompted; prompts are disabled here): $msg"
            msg.contains("Could not resolve host", true) ||
                msg.contains("Connection timed out", true) ||
                msg.contains("Network is unreachable", true) ->
                "git could not reach the remote (network): $msg"
            else -> msg
        }
    }

    // ------------------------------------------------------------------
    // Mutating (instructions_write tool; called on background thread)
    // ------------------------------------------------------------------

    /**
     * Writes [content] to [relPath] inside the clone and returns `git diff`
     * output for the approval dialog, or null when the path is invalid.
     * [relPath] is relative to the content root; git is driven with the path
     * rebased onto the repo root (clone dir), which the content root may not
     * be when a subpath is configured.
     */
    fun previewChange(relPath: String, content: String): String? {
        requireCanonical(relPath) ?: return null
        val spec = state().spec() ?: return null
        val dir = cloneDir(spec, branch())
        try {
            if (!File(dir, ".git").isDirectory) ensureClone(spec, branch())
        } catch (_: Exception) {
            return null
        }
        val root = contentRoot(dir) ?: dir
        val file = resolve(root, relPath) ?: return null
        val repoRel = if (spec.subpath.isBlank() || root == dir) relPath.trimStart('/', '\\')
        else "${spec.subpath}/${relPath.trimStart('/', '\\')}"
        return try {
            file.parentFile?.mkdirs()
            val existed = file.isFile
            if (existed && file.readText(StandardCharsets.UTF_8) == content) {
                "(no changes)"
            } else {
                file.writeText(content, StandardCharsets.UTF_8)
                git(dir, "add", "--", repoRel)
                if (existed) diff(dir) else "(new file)"
            }
        } catch (e: Exception) {
            throw IllegalStateException("failed to write '$relPath': ${e.message}", e)
        }
    }

    /**
     * Reverts the working-tree change left by [previewChange] when the user
     * declines the commit/push (keeps the clone clean for the next refresh).
     */
    fun discardChange(relPath: String) {
        requireCanonical(relPath) ?: return
        val spec = state().spec() ?: return
        val dir = cloneDir(spec, branch())
        val root = contentRoot(dir) ?: dir
        val repoRel = if (spec.subpath.isBlank() || root == dir) relPath.trimStart('/', '\\')
        else "${spec.subpath}/${relPath.trimStart('/', '\\')}"
        try {
            git(dir, "checkout", "--", repoRel)
            git(dir, "reset", "--", repoRel)
            File(root, relPath.trimStart('/', '\\')).delete()
        } catch (e: Exception) {
            LOG.warn("instructions discard failed", e)
        }
    }

    /**
     * Commits and pushes the change staged by [previewChange]. On push
     * rejection (someone pushed first) does one polite pull --rebase and
     * retries the push once; never force-pushes.
     */
    fun commitAndPush(relPath: String, message: String): String {
        requireCanonical(relPath) ?: throw IllegalStateException(
            "only the canonical instructions file may be modified"
        )
        val spec = state().spec() ?: throw IllegalStateException("instructions repo not configured")
        val dir = cloneDir(spec, branch())
        val repoRel = if (spec.subpath.isBlank()) relPath.trimStart('/', '\\')
        else "${spec.subpath}/${relPath.trimStart('/', '\\')}"
        git(dir, "add", "--", repoRel)
        git(dir, "-c", "user.name=TellForgeAI", "-c", "user.email=tellforgeai@localhost",
            "commit", "-m", message.ifBlank { "update instructions" })
        try {
            git(dir, "push", "origin", "HEAD:${branch()}")
        } catch (e: Exception) {
            git(dir, "pull", "--rebase", "origin", branch())
            git(dir, "push", "origin", "HEAD:${branch()}")
        }
        val hash = git(dir, "rev-parse", "--short", "HEAD").trim()
        reloadSnapshot(dir, spec)
        return "Committed and pushed $hash to ${branch()}."
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Hard guard: the mutating API accepts only the canonical file. This is
     * the tooling-level enforcement of "the instructions are one document" —
     * no caller (agent, UI, future tool) can write a second file here.
     * Returns null when [relPath] is not the canonical file.
     */
    private fun requireCanonical(relPath: String): String? {
        val clean = relPath.trim().trimStart('/', '\\')
        return if (clean.equals(CANONICAL_FILE, ignoreCase = true)) clean else null
    }

    private fun state(): InstructionsRepoState = InstructionsRepoState.getInstance(project)

    private fun branch(): String = state().branch()

    /**
     * The on-disk clone for the currently configured repo (may not exist yet);
     * intended for read-only inspection — the editor tab and "open in repo".
     */
    fun currentCloneDir(): File? {
        val spec = state().spec() ?: return null
        return cloneDir(spec, branch())
    }

    /** True when the clone has no pending (staged/modified) changes. */
    fun isClean(): Boolean {
        try {
            val dir = currentCloneDir() ?: return true
            if (!File(dir, ".git").isDirectory) return true
            return git(dir, "status", "--porcelain").isBlank()
        } catch (_: Exception) {
            return true
        }
    }

    /**
     * The instruction markdown for the canonical [CANONICAL_FILE], read
     * straight from the clone (no network). Returns null when the clone
     * doesn't exist yet or the file is absent.
     */
    fun readCanonicalFile(): String? {
        try {
            val dir = currentCloneDir() ?: return null
            val spec = state().spec() ?: return null
            if (!File(dir, ".git").isDirectory) return null
            val root = contentRoot(dir) ?: dir
            val f = resolve(root, CANONICAL_FILE) ?: return null
            if (f.isFile) return f.readText(StandardCharsets.UTF_8)
            return null
        } catch (_: Exception) {
            return null
        }
    }

    /** Deterministic per (url, branch) clone location under the plugin temp dir. */
    private fun cloneDir(spec: RepoSpec, branchName: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${spec.cloneUrl}|${branchName}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val base = File(FileUtil.getTempDirectory(), "tellforgeai-instructions")
        return File(base, digest)
    }

    /** Resolves [relPath] against [root] (already incl. subpath), rejecting traversal outside it. */
    private fun resolve(root: File, relPath: String): File? {
        val clean = relPath.trim().trimStart('/', '\\')
        if (clean.isEmpty()) return null
        val f = File(root, clean)
        val canonicalRoot = root.canonicalFile.parentFile?.path ?: root.canonicalFile.path
        val canonical = try { f.canonicalFile.path } catch (_: Exception) { return null }
        return if (canonical == canonicalRoot || canonical.startsWith(canonicalRoot + File.separator)) f else null
    }

    /** The clone root plus the configured subpath, or null when unconfigured. */
    private fun contentRoot(dir: File): File? {
        val sub = state().spec()?.subpath ?: return null
        return if (sub.isNullOrBlank()) dir else File(dir, sub)
    }

    private fun diff(dir: File): String = try {
        git(dir, "--no-pager", "diff", "--cached", "--stat").trim() +
            "\n\n" + git(dir, "--no-pager", "diff", "--cached").take(4000)
    } catch (_: Exception) {
        "(diff unavailable)"
    }

    /** Runs git with the default 60s timeout. */
    private fun git(dir: File, vararg args: String): String = gitTimed(dir, 60_000L, *args)

    /**
     * Runs git in [dir]; non-zero exit throws with stderr captured and friendly.
     *
     * Terminal prompts are disabled (GIT_TERMINAL_PROMPT=0 etc.) so a missing
     * credential fails fast with a readable message instead of hanging forever
     * on an invisible username/password prompt — the cause of the stuck
     * "Syncing…" in the config dialog. SSH auth uses BatchMode for the same
     * reason. A wall-clock timeout (120s for clone, 60s default) kills any
     * process that stalls for another reason (dead network etc.).
     */
    private fun gitTimed(dir: File, timeoutMs: Long, vararg args: String): String {
        val cmd = (listOf("git") + args.toList())
        val cmdLine = cmd.joinToString(" ")
        val startedAt = System.currentTimeMillis()
        InstructionsSyncLog.info("git exec (${dir.invariantSeparatorsPath}): $cmdLine")
        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(false)
        applyNoPromptEnv(pb)
        val p = pb.start()
        // Read both pipes concurrently — reading stdout to EOF before stderr
        // can deadlock when a pipe buffer fills with chatty stderr.
        val outF = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().use { it.readText() } }
        val errF = CompletableFuture.supplyAsync { p.errorStream.bufferedReader().use { it.readText() } }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            CompletableFuture.allOf(outF, errF).get(5, TimeUnit.SECONDS) // drain then discard
            InstructionsSyncLog.error("git TIMED OUT after ${timeoutMs}ms: $cmdLine")
            throw IllegalStateException("git ${args.first()} timed out after ${timeoutMs / 1000}s")
        }
        val out = outF.get(5, TimeUnit.SECONDS)
        val err = errF.get(5, TimeUnit.SECONDS)
        InstructionsSyncLog.info(
            "git exit=${p.exitValue()} in ${System.currentTimeMillis() - startedAt}ms: ${args.first()}" +
                (if (err.isNotBlank()) " [stderr: ${err.trim().take(300)}]" else ""))
        if (p.exitValue() != 0) {
            InstructionsSyncLog.error("git FAILED (exit ${p.exitValue()}): $cmdLine — ${err.trim().ifEmpty { out.trim() }}")
            throw IllegalStateException("git ${args.first()} failed (exit ${p.exitValue()}): ${err.trim().ifEmpty { out.trim() }}")
        }
        return out
    }

    /**
     * Stops git from ever prompting: no terminal username/password, no
     * GUI askpass, no interactive GCM; ssh runs in BatchMode. A missing
     * credential then produces a fast, explicit failure instead of a hang.
     */
    private fun applyNoPromptEnv(pb: ProcessBuilder) {
        pb.environment()["GIT_TERMINAL_PROMPT"] = "0"
        pb.environment()["GIT_ASKPASS"] = "echo"
        pb.environment()["GIT_SSH_COMMAND"] = "ssh -oBatchMode=yes"
        pb.environment()["GCM_INTERACTIVE"] = "Never"
        pb.environment()["SSH_ASKPASS"] = "echo"
    }

    /**
     * Reads the whole [Process] output with a wall-clock timeout; kills the
     * process and returns null when it doesn't finish in time.
     */
    private fun readAll(p: Process, timeoutMs: Long): String? {
        val outF = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().use { it.readText() } }
        return try {
            val out = outF.get(timeoutMs, TimeUnit.MILLISECONDS)
            p.waitFor(5, TimeUnit.SECONDS)
            out
        } catch (_: Exception) {
            p.destroyForcibly()
            null
        }
    }

    companion object {
        /** The canonical, user- and agent-editable instructions file. */
        const val CANONICAL_FILE = "INSTRUCTIONS.md"

        /** The canonical file relative to the clone root, honouring the subpath. */
        fun canonicalPath(subpath: String): String =
            if (subpath.isBlank()) CANONICAL_FILE else "$subpath/$CANONICAL_FILE"

        /** Content seeded into a brand-new empty instructions repo. */
        fun startInstructionsFile(): String = """# TellForge Instructions

These instructions are injected into the TellForge AI assistant's system prompt.
When the user asks to "update the TellForge instructions", change them only via
the tellforge_instructions_write tool — never create or edit instruction files
(AGENTS.md, INSTRUCTIONS.md, CLAUDE.md, etc.) in the user's project. They are
one single document — do not create additional files beside this one;
everything worth persisting lives here.

## Changelog and release versions

- Keep a `CHANGELOG.md` (Keep a Changelog format) at the repo root.
- Every commit that ships user-facing changes (feat:, fix:) adds one bullet
  under `## [Unreleased]` (### Added / Changed / Fixed / Removed) in the
  same commit, written from the user's perspective.
- The agent NEVER chooses the version number or release date, and never
  converts `## [Unreleased]` into a dated `## [X.Y.Z]` heading. The user
  curates release notes and picks the version themselves; only the user's
  explicit edit of CHANGELOG.md starts a release.
- "update the changelog" means: write/refresh bullets under
  `## [Unreleased]` only.

## Guidelines

- Keep it short: every line costs prompt tokens on every request.
- Prefer durable facts (conventions, commands, gotchas) over one-off notes.
- Delete guidance that is no longer true; stale instructions are worse than none.
"""

        @JvmStatic
        fun getInstance(project: Project): InstructionsRepoService =
            project.getService(InstructionsRepoService::class.java)
    }
}
