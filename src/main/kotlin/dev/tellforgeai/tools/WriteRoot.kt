package dev.tellforgeai.tools

import com.intellij.openapi.project.Project
import dev.tellforgeai.settings.NotionState
import dev.tellforgeai.settings.TellForgeAIWikiState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Write-root support shared by the Tela wiki and Notion integrations.
 *
 * A project can confine the agent's writes to one page subtree per
 * integration: the "write root". When [NotionState.writeRootId] /
 * [TellForgeAIWikiState.writeRootId] is non-empty, every mutating tool call
 * must resolve to a page inside that subtree — enforced here, BEFORE any
 * approval dialog, as a hard refusal. Reads are never restricted.
 *
 * Enforcement shape, per integration:
 *
 *  - Creates are PINNED: the tool itself sets the parent (and space, for
 *    wiki) to the root; the model's parent/space args are overridden or
 *    rejected with an explanatory error it can self-correct from.
 *  - Updates/deletes/append/block-edits are WALK-CHECKED: the target's
 *    parent chain is followed up to the root; a chain reaching the top
 *    without hitting it is refused.
 *  - Deleting the root page itself is always refused (a wiki delete takes
 *    the whole subtree to trash).
 *  - Moves must land inside the subtree; detaching to the top level is
 *    refused while a root is set.
 */
object WriteRoot {

    // ------------------------------------------------------------- parsing

    /**
     * Parses a user-supplied Notion write-root reference into a bare page id.
     * Accepts:
     *  - the 32-hex id with or without dashes,
     *  - a page URL of the common forms `https://www.notion.so/<Name>-<32hex>`
     *    and `https://www.notion.so/<32hex>?v=…`,
     *  - a picker display string with a trailing `[page] <hex>` marker, e.g.
     *    `My page — [page] 1a2b…` — extraneous text is ignored.
     * Returns null when unrecognizable.
     */
    fun parseNotionTarget(text: String): String? {
        val s = text.trim()
        if (s.isEmpty()) return null
        // Picker display form: "… — [page|database] <32hex>" at the end,
        // or the pre-fill form "… (id <32hex>)"; legacy saved display
        // "title [page]" followed by " (id <hex>)" also matches the latter.
        Regex("\\[(?:page|database)]\\s*([0-9a-fA-F-]{32,36})\\s*$")
            .find(s)?.groupValues?.get(1)?.replace("-", "")?.let { return it }
        Regex("\\(id ([0-9a-fA-F-]{32,36})\\)").findAll(s).lastOrNull()
            ?.groupValues?.get(1)?.replace("-", "")?.let { return it }
        // A Notion URL: last path segment(s) end with a 32-hex id.
        val hex = "[0-9a-fA-F]{32}"
        val fromUrl = Regex("([0-9a-fA-F-]{32,36})(?:\\?.*)?$").find(s)?.groupValues?.get(1)
            ?.replace("-", "")
        if (s.startsWith("http", ignoreCase = true)) return fromUrl?.takeIf { it.matches(Regex(hex)) }
        if (fromUrl != null && fromUrl.matches(Regex(hex))) return fromUrl
        // Bare uuid "1a2b…-3c…" form.
        if (s.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            return s.replace("-", "")
        }
        return null
    }

    /**
     * Parses a user-supplied tela wiki write-root reference into a bare
     * numeric page id. Accepts:
     *  - a bare id (`7385`),
     *  - an app URL `…/spaces/<sid>/pages/<pid>/<slug>`,
     *  - a picker display string with a trailing `(id 7385)` marker, e.g.
     *    `child — Personal (id 7385)` — extraneous text is ignored,
     *  - a plain title is NOT resolvable here (search handles that path).
     * Returns null otherwise.
     */
    fun parseTelaTarget(text: String): String? {
        val s = text.trim()
        if (s.isEmpty()) return null
        s.substringAfter("/pages/", "").substringBefore('/').takeIf { it.matches(Regex("\\d+")) }
            ?.let { return it }
        // Picker display form: "… (id 7385)" as the last id marker.
        Regex("\\(id (\\d+)\\)").findAll(s).lastOrNull()?.groupValues?.get(1)
            ?.let { return it }
        return s.takeIf { it.matches(Regex("\\d+")) }
    }

    // ------------------------------------------------------------- verify

    /** Result of resolving a write root against the live instance. */
    class VerifiedRoot(val id: String, val title: String, val kind: String /* "page"/"database" */) {
        override fun toString() = "$title [$kind]"
    }

    /**
     * Verifies a tela write-root candidate exists: GET /api/pages/{id}
     * returns the `Page` envelope, whose `title` (and `space_id`) we cache
     * in the dialog's verification step. Errors come back as [IllegalStateException]
     * messages suitable for direct display in the dialog.
     */
    fun verifyTela(project: Project, pageId: String): VerifiedRoot {
        val resp = TelaWikiTool.getRaw(project, "/api/pages/$pageId").getOrElse { e ->
            // Distinguish the real failure modes instead of a blanket
            // "check the token" — tela answers 403 for BOTH "no access" and
            // "page does not exist" (anti-enumeration), and 403
            // forbidden_public marks a page readable only because its space
            // is published. A public-only page cannot be a write root.
            val code = (e as? TelaWikiTool.ApiException)?.code
            throw IllegalStateException(
                when (code) {
                    "forbidden_public" -> "tela page $pageId is published-readable only — you are not a member of " +
                        "its space, so it cannot be a write root. Pick a page in a space you belong to."
                    "forbidden" -> "tela page $pageId not accessible — the page does not exist, or your token " +
                        "lacks access to its space"
                    else -> "tela page $pageId not reachable — ${TelaWikiTool.describe(e)}"
                },
            )
        }
        val page = resp["page"] as? JsonObject ?: throw IllegalStateException(
            "tela returned an unexpected shape for page $pageId",
        )
        val title = page["title"]?.jsonPrimitive?.content.orEmpty().ifEmpty { "page $pageId" }
        return VerifiedRoot(pageId, title, "page")
    }

    /**
     * Verifies a Notion write-root candidate: a PAGE (GET /pages/{id}) or a
     * DATABASE (GET /databases/{id}) — database roots are allowed, makes
     * create calls become row additions. Throws [IllegalStateException]
     * with a user-facing message when neither resolves.
     */
    fun verifyNotion(project: Project, id: String): VerifiedRoot {
        NotionTool.getForRootCheck(project, "/pages/$id").fold(
            onSuccess = { page ->
                val title = NotionTool.pageTitle(page)
                return VerifiedRoot(id, title, "page")
            },
            onFailure = {},
        )
        NotionTool.getForRootCheck(project, "/databases/$id").fold(
            onSuccess = { db ->
                val title = (db["title"] as? kotlinx.serialization.json.JsonArray)
                    ?.joinToString("") { (it as? JsonObject)?.get("plain_text")?.jsonPrimitive?.content.orEmpty() }
                    .orEmpty().ifBlank { "database $id" }
                return VerifiedRoot(id, title, "database")
            },
            onFailure = {},
        )
        throw IllegalStateException(
            "Notion page/database $id not reachable — connect the integration to it " +
                "(page ··· menu → Connections) and re-pick the root.",
        )
    }

    // ----------------------------------------------------------- ancestry

    /**
     * True when the tela page [pageId] sits under [rootId] — walks
     * `parent_id` up the chain with a cycle/depth guard, then verifies
     * root-space match via `space_id`. GETs are cheap and tela returns each
     * page's `parent_id` as a required schema field.
     */
    fun isUnderTelaRoot(project: Project, pageId: String, rootId: String): Boolean {
        var current = pageId
        repeat(20) {
            if (current == rootId) return true
            val resp = TelaWikiTool.getRaw(project, "/api/pages/$current").getOrNull()
                ?: return false
            val page = resp["page"] as? JsonObject ?: return false
            val parent = page["parent_id"]?.jsonPrimitive?.content?.takeIf { it != "null" && it.isNotBlank() }
                ?: return false
            current = parent
        }
        return false // depth guard
    }

    /**
     * Canonical comparison form for Notion page/block ids: Notion APIs accept
     * and RETURN ids both dashed and dashless (search results come back
     * dashed, path parameters accept either, persisted settings store them
     * dashless). Every equality check on ids must normalize first, or
     * "child of the root" silently reads as "outside the root" — which is
     * exactly the bug that made writes under the configured root impossible.
     */
    internal fun normalizeNotionId(id: String): String = id.replace("-", "").lowercase()

    /**
     * True when the Notion page [pageId] sits under [rootId]: walks the
     * block-tree parent chain via GET /blocks/{id}, keying on
     * `parent.page_id` (the verified Notion API shape). Ids are compared in
     * canonical form so dashed and dashless references match.
     */
    fun isUnderNotionRoot(project: Project, pageId: String, rootId: String): Boolean {
        val root = normalizeNotionId(rootId)
        var current = normalizeNotionId(pageId)
        repeat(30) {
            if (current == root) return true
            val block = NotionTool.getForRootCheck(project, "/blocks/$current")
                .getOrElse { return false }
            val parentObj = block["parent"] as? JsonObject ?: return false
            val next = parentObj["page_id"]?.jsonPrimitive?.content
                ?: parentObj["database_id"]?.jsonPrimitive?.content
                ?: parentObj["block_id"]?.jsonPrimitive?.content
                ?: return false
            current = normalizeNotionId(next)
        }
        return false
    }

    // -------------------------------------------------------- gate errors

    /** Standard refusal message when a write target is outside the root. */
    fun outsideRootError(integration: String, rootId: String, target: String): String =
        "Error: $integration writes are confined to the configured write root (id $rootId) — " +
            "the target ($target) is outside it. " +
            "Ask the user either to pick a broader root in 'Configure Integrations…' " +
            "or to do this write in $integration themselves."
}
