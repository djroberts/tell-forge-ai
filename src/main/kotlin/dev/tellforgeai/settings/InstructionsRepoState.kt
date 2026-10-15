package dev.tellforgeai.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Per-project configuration for the "instructions repo": a separate (usually
 * private, versioned) git repository that holds AI agent instructions which
 * should NOT live in the project's own (possibly public) repository.
 *
 * The repository URL and branch roam with the project; no credentials are
 * stored — a `git clone` uses the user's existing terminal git credentials
 * (credential helper / SSH). [instructionsEnabled] is the master on/off
 * switch: when false the instructions layer is not injected into the system
 * prompt and the instructions_* tools are not offered to the model.
 * [instructionsWriteEnabled] is the one-time opt-in that lets the model
 * modify the instructions repo in Forge mode; it is only settable from the
 * Configure Instructions dialog.
 *
 * URL parsing (see [RepoSpec.parse]) accepts:
 *  - https://github.com/owner/repo.git
 *  - git@host:owner/repo.git
 *  - owner/repo            (shorthand for https://github.com/owner/repo)
 *  - owner/repo/subdir     (subdirectory inside the repo — top level == "/")
 *  - any of the above with a trailing "#subdir" fragment instead
 */
@Service(Service.Level.PROJECT)
@State(name = "TellForgeAIInstructionsRepoState", storages = [Storage("tellforgeai.instructions.xml")])
class InstructionsRepoState : PersistentStateComponent<InstructionsRepoState.State> {

    class State {
        /** Repo URL in any accepted form, empty = not configured. */
        var repoUrl: String = ""

        /** Branch to clone. */
        var branch: String = "main"

        /** Master switch: inject the instructions layer and offer the tools. */
        var instructionsEnabled: Boolean = false

        /** One-time opt-in: allow mutating instructions_* tools in Forge mode. */
        var instructionsWriteEnabled: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun repoUrl(): String = state.repoUrl.trim()

    fun setRepoUrl(value: String) {
        state.repoUrl = value.trim()
    }

    fun branch(): String = state.branch.trim().ifEmpty { "main" }

    fun setBranch(value: String) {
        state.branch = value.trim()
    }

    fun enabled(): Boolean = state.instructionsEnabled && repoUrl().isNotEmpty()

    fun setEnabled(value: Boolean) {
        state.instructionsEnabled = value
    }

    fun writeEnabled(): Boolean = state.instructionsWriteEnabled && enabled()

    fun setWriteEnabled(value: Boolean) {
        state.instructionsWriteEnabled = value
    }

    /** Configured = a repo URL is set (no secrets involved). */
    fun configured(): Boolean = RepoSpec.parse(repoUrl()) != null

    /** The parsed repo spec, or null when the URL is not a recognizable form. */
    fun spec(): RepoSpec? = RepoSpec.parse(repoUrl())

    companion object {
        @JvmStatic
        fun getInstance(project: Project): InstructionsRepoState =
            project.getService(InstructionsRepoState::class.java)
    }
}

/**
 * A parsed instructions-repo location: the URL to clone plus the subdirectory
 * inside the clone that holds the instruction markdown files.
 */
data class RepoSpec(val cloneUrl: String, val subpath: String) {

    companion object {
        /** Parses one of the accepted URL forms; null when the input is unparseable. */
        fun parse(raw: String): RepoSpec? {
            var s = raw.trim().trimEnd('/')
            if (s.isEmpty()) return null
            // Optional "#subdir" fragment (highest precedence).
            var subpath = ""
            val hash = s.indexOf('#')
            if (hash >= 0) {
                subpath = s.substring(hash + 1).trim('/')
                s = s.substring(0, hash).trimEnd('/')
            }
            val cloneUrl = when {
                s.startsWith("https://") || s.startsWith("http://") || s.startsWith("ssh://") || s.startsWith("git@") ->
                    s + if (s.endsWith(".git")) "" else ".git"
                // Shorthand: owner/repo[/subdir] on github.com. Owner must not
                // contain a dot, so "example.com/repo" (full host) is rejected.
                Regex("^[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+(/[A-Za-z0-9_./-]+)?$").matches(s) -> {
                    val parts = s.split('/', limit = 4)
                    if (parts.size > 2) subpath = parts.drop(2).joinToString("/").trim('/')
                    "https://github.com/${parts[0]}/${parts[1]}.git"
                }
                else -> return null
            }
            return RepoSpec(cloneUrl, subpath)
        }
    }
}
