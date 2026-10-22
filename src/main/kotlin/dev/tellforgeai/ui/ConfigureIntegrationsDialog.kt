package dev.tellforgeai.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.FormBuilder
import kotlinx.serialization.json.jsonPrimitive
import dev.tellforgeai.llm.InstructionsRepoService
import dev.tellforgeai.settings.InstructionsRepoState
import dev.tellforgeai.settings.NotionState
import dev.tellforgeai.settings.RepoSpec
import dev.tellforgeai.settings.SentryState
import dev.tellforgeai.settings.TellForgeAIWikiState
import dev.tellforgeai.settings.WikiWriteAction
import dev.tellforgeai.tools.NotionTool
import dev.tellforgeai.tools.SentryTool
import dev.tellforgeai.tools.TelaWikiTool
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField

/**
 * Per-project configuration for all integrations, opened via the
 * Integrations button in the tool window. Three tabs, each with a one-line
 * info banner at the top (also used as the tab tooltip) so someone landing
 * on a tab cold can tell what it does:
 *
 *  - Tela wiki: enable toggle, instance URL, per-project access token
 *    (stored in PasswordSafe under a per-project key — dots in the field mean
 *    a token IS saved for THIS project), connection test, write opt-in with
 *    per-action "Ask permission" toggles (add / update / delete page).
 *  - Notion: enable toggle, internal-integration token, connection test,
 *    write opt-in with the same per-action toggles.
 *  - AI instructions: a separate (usually private) git repo holding markdown
 *    instructions injected into the agent's system prompt; enable toggle,
 *    repo URL, branch, sync test, write opt-in (git commit + push).
 *  - Sentry: enable toggle, org slug, optional project slug, base URL, auth
 *    token (stored in PasswordSafe per project), connection test. All tools
 *    are read-only — this integration lets the agent investigate errors the
 *    application has pushed to Sentry, it never mutates anything.
 *
 * Each tab owns its own form + [apply]; the dialog's OK button calls
 * [IntegrationTab.apply] on all three. Existing per-dialog behaviour is
 * preserved exactly: writes can only be enabled when the enable toggle is
 * ticked AND the configuration is valid, and an empty token field clears the
 * stored token. Unticking an enable toggle removes the corresponding tools
 * from the model's tool list for this project entirely.
 */
class ConfigureIntegrationsDialog(private val project: Project) : DialogWrapper(project) {

    /** Add new integrations here — each tab owns its own form + persistence. */
    private val tabs = listOf(
        WikiTab(project),
        NotionTab(project),
        InstructionsTab(project),
        SentryTab(project),
    )

    init {
        title = "Configure Integrations"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val pane = JBTabbedPane()
        tabs.forEachIndexed { i, tab ->
            pane.addTab(tab.title, tab.panel)
            pane.setToolTipTextAt(i, tab.info)
        }
        return pane
    }

    override fun doOKAction() {
        tabs.forEach { it.apply() }
        super.doOKAction()
    }

    companion object {
        fun show(project: Project) {
            ConfigureIntegrationsDialog(project).show()
        }
    }
}

/** Common shape of a tab: knows its own persistence and validation. */
private interface IntegrationTab {
    val title: String
    /** One-line plain-text explanation, shown under the tab's header and as the tab tooltip. */
    val info: String
    val panel: JComponent
    /** Persist the form; only called on OK. */
    fun apply()
}

// ----------------------------------------------------------------------
// Shared helpers
// ----------------------------------------------------------------------

/** Info banner shown at the top of every tab: info icon + wrapped text. */
private fun infoBanner(info: String): JComponent = JBLabel(
    "<html><body style='width:380px'>$info</body></html>",
    AllIcons.General.Information,
    javax.swing.SwingConstants.LEFT,
).apply {
    verticalTextPosition = javax.swing.SwingConstants.TOP
    horizontalTextPosition = javax.swing.SwingConstants.LEFT
}

/**
 * The write-root field shared by the wiki and Notion tabs: a plain text
 * field the user types a search into or PASTES an id/URL into, with a
 * suggestions popup fed by generation-guarded background searches, plus a
 * status label that verifies the resolved root against the live instance.
 *
 * Design notes (this replaces an earlier JComboBox attempt that fought
 * Swing at every step — editor resets on model refill, action-event
 * feedback loops, "interrupted" spam from cancelled futures):
 *
 *  - A [generation] counter is the single staleness source of truth: every
 *    keystroke bumps it; every background completion captures its number
 *    up front and discards everything silently if the counter has since
 *    moved on. No stale refills, no race-y status writes, no cancels.
 *  - Interruptions/cancellations are lifecycle, not errors: dropped.
 *  - Suggestions appear in a lightweight list popup anchored under the
 *    field; the text field itself is never repopulated or reset — a
 *    suggestion click writes the text once and verifies it.
 *
 * Empty text = writes allowed anywhere. Nothing here blocks OK.
 */
private class WriteRootField(
    private val project: Project,
    private val kind: RootKind,
) {
    enum class RootKind { TELA, NOTION }

    /** Entry widget — a plain text field, no combo mechanics. */
    val field = javax.swing.JTextField()

    val status = JLabel(" ")

    /** Used by the tabs' pre-fill and apply() wiring. */
    fun text(): String = field.text

    private val executor = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService()
    private val generation = java.util.concurrent.atomic.AtomicLong()

    /**
     * Suppressed when set: the pre-fill write (title + id from saved state)
     * must not trigger a pointless background search on dialog open — it
     * only needs verify. Set by the tab pre-fills before setText; consumed
     * by the listener; every later change searches normally.
     */
    var suppressNextSearch = false

    init {
        field.columns = 28
        field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private var last = ""
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = changed()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = changed()
            private fun changed() {
                // Programmatic writes from a suggestion click also land here;
                // both are just "text changed", one flow.
                if (last == field.text) return
                last = field.text
                if (last.isBlank()) {
                    generation.incrementAndGet() // invalidate in-flight work
                    status.text = WRITE_ANYWHERE_NOTE
                    status.foreground = java.awt.Color.GRAY
                    return
                }
                if (suppressNextSearch) {
                    // Pre-fill write: verify only, no search.
                    suppressNextSearch = false
                    verify(last)
                    return
                }
                scheduleSearch(last)
            }
        })
    }

    /** Generation-guarded search: results for superseded attempts dropped. */
    private fun scheduleSearch(text: String) {
        val myGen = generation.incrementAndGet()
        status.text = "Searching…"
        status.foreground = java.awt.Color.GRAY
        executor.execute {
            val outcome = try {
                val list = when (kind) {
                    RootKind.TELA -> telaSuggestions(text)
                    RootKind.NOTION -> notionSuggestions(text)
                }
                ids = list.map { it.second }
                Outcome(list.map { it.first })
            } catch (e: Exception) {
                Outcome(null, e.message ?: e.javaClass.simpleName)
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                // Stale attempt: a newer keystroke has superseded this one —
                // drop everything silently (this is also where a cancelled /
                // interrupted search's result dies without touching the UI).
                if (generation.get() != myGen) return@invokeLater
                showSuggestions(text, outcome)
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }

    /**
     * The raw ids corresponding to the last [Outcome] display list, set when
     * the search completes on the background thread — used by the popup's
     * chosen-callback so it never has to scrape ids out of display text.
     * Only read on the EDT together with that same outcome.
     */
    private var ids: List<String> = emptyList()

    private data class Outcome(val list: List<String>?, val error: String? = null)
    /** Currently open suggestions popup, if any — closed before the next refill. */
    private var openPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    /** Renders the current attempt's outcome: popup + terminal status. */
    private fun showSuggestions(query: String, outcome: Outcome) {
        val list = outcome.list.orEmpty()
        val parsed = when (kind) {
            RootKind.TELA -> dev.tellforgeai.tools.WriteRoot.parseTelaTarget(query)
            RootKind.NOTION -> dev.tellforgeai.tools.WriteRoot.parseNotionTarget(query)
        }

        // A parsed id/URL always wins: verify it and skip the fuzzy stuff.
        if (parsed != null) {
            verify(query)
            return
        }

        status.foreground = when {
            outcome.error != null -> java.awt.Color.RED
            list.isEmpty() -> java.awt.Color.GRAY
            else -> java.awt.Color(0, 128, 0)
        }
        status.text = when {
            outcome.error != null ->
                "<html><font color='red'>Search failed: ${esc(outcome.error).take(150)}</font></html>"
            list.isEmpty() ->
                "No matches for '${esc(query)}' — or paste an id/URL."
            else -> "${list.size} match(es) — click one below:"
        }
        if (list.isEmpty()) return

        // IntelliJ chooser popup anchored BELOW the field (never on top of it —
        // a popup that covers the field hides what was typed and, when it grabs
        // focus, stops further keystrokes from landing in the field at all,
        // which made it "not work"). Unfocusable, so typing continues in the
        // field and each new refill simply swaps the list underneath.
        openPopup?.cancel()
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(list)
            .setTitle("write root")
            // Must NOT take focus: a focusable popup steals the keyboard from
            // the field the moment it appears, so the next keystroke goes
            // nowhere and the user has to click back into the field after
            // every search. Unfocusable keeps typing alive in the field.
            .setRequestFocus(false)
            .setRenderer(javax.swing.DefaultListCellRenderer().apply {
                // Leave default rendering; text already carries title + tag + id.
            })
            .setItemChosenCallback { selected ->
                // Keep the HUMAN-READABLE reference in the field (title +
                // breadcrumb + id), never a bare machine id — a bare id is
                // unreadable to the user and, on Notion, turns a nice URL
                // slug into an opaque 32-hex uuid. The parsers
                // (WriteRoot.parseTelaTarget / parseNotionTarget) extract
                // the id from this display form, so verify/persist still
                // work; parallel `ids` keeps index alignment.
                field.text = selected
            }
            .createPopup()
        openPopup = popup
        popup.show(com.intellij.ui.awt.RelativePoint(
            field, java.awt.Point(0, field.height + 2),
        ))
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ------------------------------------------------------------ suggesters

    private fun telaSuggestions(query: String): List<Pair<String, String>> {
        val hits = telaSearchHits(query)
        val rows = mutableListOf<Pair<String, String>>()
        for (h in hits) {
            val o = h as? kotlinx.serialization.json.JsonObject ?: continue
            val title = o["title"]?.toString()?.trim('"').orEmpty()
            val id = (o["page_id"] ?: o["id"])?.toString()?.trim('"').orEmpty()
            if (id.isEmpty()) continue
            // Breadcrumb (Space › Parent › …) is always present on tela search
            // hits and is the only way to tell same-titled pages apart.
            val crumb = (o["breadcrumb"] as? kotlinx.serialization.json.JsonArray)
                ?.joinToString(" › ") { it.jsonPrimitive.content }
                .orEmpty()
            val public = o["public"]?.toString() == "true"
            val display = buildString {
                append(title.ifEmpty { "(untitled)" })
                if (crumb.isNotEmpty()) append(" — ").append(crumb)
                if (public) append("  (public)")
                append("  (id ").append(id).append(')')
            }
            rows += display to id
        }
        // tela's /api/search ranks full-text over titles AND bodies, so body
        // matches drown out the page actually titled like the query. Bubble
        // title matches to the front before the display cut.
        val q = query.trim().lowercase()
        val ranked = rows.sortedByDescending { it.first.substringBefore(" — ").lowercase().contains(q) }
        if (ranked.isNotEmpty()) return ranked.take(8)

        // Fallback for short prefixes ("ch" → "child") and zero-hit queries:
        // /api/search is ranked Postgres full-text and needs whole tokens, so
        // one- and two-letter queries usually return nothing even when the
        // page exists. Walk each space's page tree and match titles client-
        // side instead. Caller wraps this in a background thread already.
        if (q.length >= 3) return emptyList() // real query, genuinely no hits
        return telaTreeScan(q)
    }

    /**
     * Raw /api/search hit list. Failure (bad token, host down) THROWS so the
     * caller's error path shows "Search failed: …" instead of a quiet
     * "No matches"; it is only called once per keystroke attempt.
     */
    private fun telaSearchHits(query: String): List<kotlinx.serialization.json.JsonElement> {
        val resp = dev.tellforgeai.tools.TelaWikiTool.getRaw(
            project, "/api/search?q=" + java.net.URLEncoder.encode(query, Charsets.UTF_8),
        ).getOrElse { throw IllegalStateException(TelaWikiTool.describe(it)) }
        return resp["results"] as? kotlinx.serialization.json.JsonArray ?: emptyList()
    }

    /**
     * Lists spaces, flattens each space's page tree (`tree=1`), and keeps
     * pages whose title contains [q] (any case), capped at 8. Returns an
     * empty list when the token cannot list spaces (no throw — the search
     * status line already handles the error case).
     */
    private fun telaTreeScan(q: String): List<Pair<String, String>> {
        val spaces = try {
            dev.tellforgeai.tools.TelaWikiTool.getRaw(project, "/api/spaces").getOrNull()
        } catch (_: Exception) {
            null
        }?.get("spaces") as? kotlinx.serialization.json.JsonArray ?: return emptyList()

        val out = mutableListOf<Pair<String, String>>()
        for (s in spaces) {
            val o = s as? kotlinx.serialization.json.JsonObject ?: continue
            val sid = o["id"]?.jsonPrimitive?.content ?: continue
            val sname = o["name"]?.jsonPrimitive?.content.orEmpty()
            val tree = try {
                dev.tellforgeai.tools.TelaWikiTool.getRaw(
                    project, "/api/pages?space_id=$sid&tree=1",
                ).getOrNull()
            } catch (_: Exception) {
                null
            }?.get("pages") as? kotlinx.serialization.json.JsonArray ?: continue
            walkTree(tree, q, sname, out)
            if (out.size >= 8) break
        }
        return out.take(8)
    }

    /**
     * Depth-first flatten of a /api/pages?tree=1 `pages` array. [query]
     * is the lowercase title filter; [crumb] (space name) is display-only.
     */
    private fun walkTree(
        nodes: kotlinx.serialization.json.JsonArray,
        query: String,
        crumb: String,
        out: MutableList<Pair<String, String>>,
    ) {
        for (n in nodes) {
            val o = n as? kotlinx.serialization.json.JsonObject ?: continue
            val title = o["title"]?.jsonPrimitive?.content.orEmpty()
            val id = o["id"]?.jsonPrimitive?.content
                ?: o["page_id"]?.jsonPrimitive?.content
                ?: continue
            if (title.lowercase().contains(query)) {
                val display = buildString {
                    append(title.ifEmpty { "(untitled)" })
                    if (crumb.isNotEmpty()) append(" — ").append(crumb)
                    append("  (id ").append(id).append(')')
                }
                out += display to id
                if (out.size >= 8) return
            }
            (o["children"] as? kotlinx.serialization.json.JsonArray)?.let {
                walkTree(it, query, crumb, out)
            }
            if (out.size >= 8) return
        }
    }

    private fun notionSuggestions(query: String): List<Pair<String, String>> {
        val result = dev.tellforgeai.tools.NotionTool.searchForPicker(project, query)
        val resp = result.getOrElse { throw IllegalStateException(
            NotionTool.describePublic(it),
        ) }
        val hits = resp["results"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return hits.mapNotNull { h ->
            val o = h as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val id = o["id"]?.toString()?.trim('"').orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            val tag = if (o["object"]?.toString()?.contains("database") == true) "database" else "page"
            // Notion returns page titles in properties / databases in the
            // top-level `title` array; reuse the existing extraction for
            // pages instead of re-implementing it.
            val title = when (tag) {
                "database" -> (o["title"] as? kotlinx.serialization.json.JsonArray)
                    ?.joinToString("") { (it as? kotlinx.serialization.json.JsonObject)?.get("plain_text")?.jsonPrimitive?.content.orEmpty() }
                    .orEmpty()
                    .ifBlank { "database" }
                else -> dev.tellforgeai.tools.NotionTool.pageTitle(o).takeIf { it != "(untitled)" }
                    ?: "(untitled)"
            }
            val display = "$title — [$tag] $id"
            display to id
        }.take(8)
    }

    /**
     * Verifies the entered root (id or URL) against the live instance and
     * colors the status label accordingly. Non-blocking.
     */
    fun verify(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            status.text = WRITE_ANYWHERE_NOTE
            status.foreground = java.awt.Color.GRAY
            return
        }
        val id = when (kind) {
            RootKind.TELA -> dev.tellforgeai.tools.WriteRoot.parseTelaTarget(trimmed) ?: run {
                status.text = "<html><font color='red'>Not a numeric id or …/spaces/…/pages/&lt;id&gt; URL.</font></html>"
                return
            }
            RootKind.NOTION -> dev.tellforgeai.tools.WriteRoot.parseNotionTarget(trimmed) ?: run {
                status.text = "<html><font color='red'>Not a 32-hex id or notion.so URL.</font></html>"
                return
            }
        }
        status.text = "Verifying root…"
        status.foreground = java.awt.Color.GRAY
        executor.submit {
            val result = runCatching {
                when (kind) {
                    RootKind.TELA -> dev.tellforgeai.tools.WriteRoot.verifyTela(project, id)
                    RootKind.NOTION -> dev.tellforgeai.tools.WriteRoot.verifyNotion(project, id)
                }
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                result.fold(
                    onSuccess = { root ->
                        status.text = "<html><font color='#008000'>✓ ${root.title} [${root.kind}]</font></html>"
                    },
                    onFailure = { e ->
                        status.text = "<html><font color='red'>✗ ${e.message?.take(200)}</font></html>"
                    },
                )
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }

    /** Current text of the field. */

    companion object {
        const val WRITE_ANYWHERE_NOTE =
            "empty = writes allowed anywhere (not recommended for write-enabled projects)"
    }
}

// ----------------------------------------------------------------------
// Tela wiki tab
// ----------------------------------------------------------------------

private class WikiTab(private val project: Project) : IntegrationTab {

    private val state = TellForgeAIWikiState.getInstance(project)

    override val title = "Tela wiki"
    override val info = "Tela is your team's shared wiki (an instance URL plus a per-project access token). " +
        "The agent can look things up here, and — if you allow writes — create, update and delete pages with what it learns."

    private val enable = JBCheckBox("Enable Tela wiki tools for this project", state.enabled())
    private val urlField = JTextField(state.wikiUrl(), 34)
    private val tokenField = JBPasswordField().apply {
        // Pre-fill from THIS project's PasswordSafe entry (or empty when none).
        state.token(project)?.let { text = it }
    }
    private val write = JBCheckBox(
        "Allow the agent to create, update and delete wiki pages",
        state.writeEnabled(),
    )
    private val askCreate = JBCheckBox("Ask permission to add page", !state.skipApproval(WikiWriteAction.CREATE))
    private val askUpdate = JBCheckBox("Ask permission to update page", !state.skipApproval(WikiWriteAction.UPDATE))
    private val askDelete = JBCheckBox("Ask permission to delete page", !state.skipApproval(WikiWriteAction.DELETE))
    private val testLabel = JLabel(" ")

    private val writeRoot = WriteRootField(project, WriteRootField.RootKind.TELA).apply {
        val savedId = state.writeRootId()
        if (savedId.isNotEmpty()) {
            // Self-describing pre-fill: title + id, so the parsers can always
            // recover the id and the user sees a readable reference. Then
            // re-verify against the live instance — the page may have been
            // deleted/renamed since the last OK.
            // The document listener fires synchronously on setText; set the
            // flag first so the pre-fill verifies instead of searching.
            suppressNextSearch = true
            field.text = "${state.writeRootDisplay().ifEmpty { "(page " + savedId + ")" }} (id $savedId)"
        } else {
            status.text = WriteRootField.WRITE_ANYWHERE_NOTE
            status.foreground = java.awt.Color.GRAY
        }
    }

    override val panel: JComponent = FormBuilder.createFormBuilder()
        .addComponent(infoBanner(info), 4)
        .addComponent(enable, 4)
        .addLabeledComponent("Wiki URL:", urlField)
        .addLabeledComponent("Access token:", tokenField)
        .addComponent(testButton(), 4)
        .addComponent(testLabel, 4)
        .addComponent(write, 8)
        .addComponent(TitledSeparator("Writes are confined to"), 8)
        .addLabeledComponent("Root page (type to search or paste id/URL):", writeRoot.field)
        .addComponent(writeRoot.status, 4)
        .addComponent(TitledSeparator("Before each write: ask for permission"), 8)
        .addComponent(askCreate, 4)
        .addComponent(askUpdate, 4)
        .addComponent(askDelete, 4)
        .panel

    private fun testButton() = JButton("Test connection").apply {
        addActionListener {
            val token = String(tokenField.password).trim()
            testLabel.text = TelaWikiTool.testConnection(urlField.text.trim(), token)
        }
    }

    override fun apply() {
        state.setEnabled(enable.isSelected)
        state.setWikiUrl(urlField.text)
        val token = String(tokenField.password).trim()
        if (token.isEmpty()) {
            // Empty field = clear this project's stored token (dots only appear
            // when a token was actually saved; a cleared field stays cleared).
            state.setToken(project, "")
        } else {
            state.setToken(project, token)
        }
        // Writes can only be enabled with a working, saved configuration.
        state.setWriteEnabled(write.isSelected && enable.isSelected && state.configured(project))
        state.setSkipApproval(WikiWriteAction.CREATE, !askCreate.isSelected)
        state.setSkipApproval(WikiWriteAction.UPDATE, !askUpdate.isSelected)
        state.setSkipApproval(WikiWriteAction.DELETE, !askDelete.isSelected)

        // Write root: re-verify on OK so stale/invalid ids never persist
        // silently; on failure keep empty (unrestricted) and surface why.
        val entered = writeRoot.text().trim()
        if (entered.isEmpty()) {
            state.setWriteRootId("")
            state.setWriteRootDisplay("")
            state.setWriteRootSpaceId("")
        } else {
            val id = dev.tellforgeai.tools.WriteRoot.parseTelaTarget(entered)
            if (id == null) {
                state.setWriteRootId("")
            } else {
                runCatching { dev.tellforgeai.tools.WriteRoot.verifyTela(project, id) }.fold(
                    onSuccess = { root ->
                        state.setWriteRootId(id)
                        state.setWriteRootDisplay(root.title)
                        // Capture the root page's space for create-coercion.
                        val spaceId = runCatching {
                            dev.tellforgeai.tools.TelaWikiTool.getRaw(project, "/api/pages/$id")
                                .getOrNull()?.get("page")
                                ?.let { it as? kotlinx.serialization.json.JsonObject }
                                ?.get("space_id")?.toString()?.trim('"')
                        }.getOrNull().orEmpty()
                        state.setWriteRootSpaceId(spaceId)
                    },
                    onFailure = { e ->
                        state.setWriteRootId("")
                        testLabel.text = "<html><font color='red'>Write root not saved: ${e.message?.take(200)}</font></html>"
                    },
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// Notion tab
// ----------------------------------------------------------------------

private class NotionTab(private val project: Project) : IntegrationTab {

    private val state = NotionState.getInstance(project)

    override val title = "Notion"
    override val info = "Connect a Notion internal integration so the agent can search your workspace, " +
        "read pages, and — if you allow writes — create, update and delete pages on your behalf."

    private val enable = JBCheckBox("Enable Notion tools for this project", state.enabled())
    private val tokenField = JBPasswordField().apply {
        state.token(project)?.let { text = it }
    }
    private val write = JBCheckBox(
        "Allow the agent to create, update and delete Notion pages",
        state.writeEnabled(),
    )
    private val askCreate = JBCheckBox("Ask permission to add page", !state.skipApproval(WikiWriteAction.CREATE))
    private val askUpdate = JBCheckBox("Ask permission to update page", !state.skipApproval(WikiWriteAction.UPDATE))
    private val askDelete = JBCheckBox("Ask permission to delete page", !state.skipApproval(WikiWriteAction.DELETE))
    private val testLabel = JLabel(" ")

    private val writeRoot = WriteRootField(project, WriteRootField.RootKind.NOTION).apply {
        val savedId = state.writeRootId()
        if (savedId.isNotEmpty()) {
            // The document listener fires synchronously on setText; set the
            // flag first so the pre-fill verifies instead of searching.
            suppressNextSearch = true
            field.text = "${state.writeRootDisplay().ifEmpty { "page/database " + savedId }} (id $savedId)"
        } else {
            status.text = WriteRootField.WRITE_ANYWHERE_NOTE
            status.foreground = java.awt.Color.GRAY
        }
    }

    override val panel: JComponent = FormBuilder.createFormBuilder()
        .addComponent(infoBanner(info), 4)
        .addComponent(enable, 4)
        .addLabeledComponent("Integration token:", tokenField)
        .addComponent(testButton(), 4)
        .addComponent(testLabel, 4)
        .addComponent(write, 8)
        .addComponent(TitledSeparator("Writes are confined to"), 8)
        .addLabeledComponent("Root page/database (type to search or paste id/URL):", writeRoot.field)
        .addComponent(writeRoot.status, 4)
        .addComponent(TitledSeparator("Before each write: ask for permission"), 8)
        .addComponent(askCreate, 4)
        .addComponent(askUpdate, 4)
        .addComponent(askDelete, 4)
        .panel

    private fun testButton() = JButton("Test connection").apply {
        addActionListener {
            val token = String(tokenField.password).trim()
            testLabel.text = NotionTool.testConnection(token)
        }
    }

    override fun apply() {
        state.setEnabled(enable.isSelected)
        val token = String(tokenField.password).trim()
        if (token.isEmpty()) {
            state.setToken(project, "")
        } else {
            state.setToken(project, token)
        }
        state.setWriteEnabled(write.isSelected && enable.isSelected && state.configured(project))
        state.setSkipApproval(WikiWriteAction.CREATE, !askCreate.isSelected)
        state.setSkipApproval(WikiWriteAction.UPDATE, !askUpdate.isSelected)
        state.setSkipApproval(WikiWriteAction.DELETE, !askDelete.isSelected)

        // Write root: re-verify on OK so stale/invalid ids never persist
        // silently; on failure keep empty (unrestricted) and surface why.
        val entered = writeRoot.text().trim()
        if (entered.isEmpty()) {
            state.setWriteRootId("")
            state.setWriteRootDisplay("")
        } else {
            val id = dev.tellforgeai.tools.WriteRoot.parseNotionTarget(entered)
            if (id == null) {
                state.setWriteRootId("")
            } else {
                runCatching { dev.tellforgeai.tools.WriteRoot.verifyNotion(project, id) }.fold(
                    onSuccess = { root ->
                        state.setWriteRootId(id)
                        // Display is title-only; the field renders
                        // "title (id N)" on next open and the parsers
                        // recover the id from that form.
                        state.setWriteRootDisplay(root.title)
                    },
                    onFailure = { e ->
                        state.setWriteRootId("")
                        testLabel.text = "<html><font color='red'>Write root not saved: ${e.message?.take(200)}</font></html>"
                    },
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// AI instructions tab
// ----------------------------------------------------------------------

private class InstructionsTab(private val project: Project) : IntegrationTab {

    private val state = InstructionsRepoState.getInstance(project)

    override val title = "AI instructions"
    override val info = "A separate, versioned git repo (outside this project) holding markdown instructions for the " +
        "agent — conventions, commands, gotchas. Its content is injected into the agent's system prompt on every " +
        "request, and the agent can be allowed to commit changes there."

    private val enable = JBCheckBox("Enable instructions repo layer for this project", state.enabled())
    private val urlField = JTextField(state.repoUrl(), 34)
    private val branchField = JTextField(state.branch(), 10)
    private val write = JBCheckBox(
        "Allow the agent to update the instructions repo (git commit + push)",
        state.writeEnabled(),
    )
    private val testLabel = JLabel(" ").apply {
        verticalAlignment = javax.swing.SwingConstants.TOP
    }

    // Wrapped in a NORTH-anchored BorderLayout so the form is pinned to the
    // top of the tab and all leftover vertical space lands at the bottom —
    // otherwise the GridBag rows stretch and a large dead gap appears above
    // the info banner.
    override val panel: JComponent = javax.swing.JPanel(java.awt.BorderLayout()).apply {
        isOpaque = false
        add(
            FormBuilder.createFormBuilder()
                .addComponent(infoBanner(info), 4)
                .addComponent(enable, 4)
                .addLabeledComponent("Repo URL:", urlField)
                .addLabeledComponent("Branch:", branchField)
                .addComponent(testButton(), 4)
                .addComponent(testLabel(), 4)
                .addComponent(write, 4)
                .panel,
            java.awt.BorderLayout.NORTH,
        )
    }

    /** Wrapping multi-line result label so git errors/notes are fully visible. */
    private fun testLabel(): JComponent = javax.swing.JPanel(java.awt.BorderLayout()).apply {
        isOpaque = false
        add(testLabel, java.awt.BorderLayout.CENTER)
    }

    private fun testButton() = JButton("Test / Sync now").apply {
        addActionListener {
            if (!isEnabled) return@addActionListener // a sync is already running
            val spec = RepoSpec.parse(urlField.text)
            if (spec == null) {
                testLabel.text =
                    "<html>Not a recognizable repo URL — try <code>owner/repo</code>, " +
                    "<code>owner/repo/subdir</code> or a full <code>https://…</code> URL.</html>"
                return@addActionListener
            }
            testLabel.text = "Syncing…"
            dev.tellforgeai.llm.InstructionsSyncLog
                .mark("integrations dialog test clicked (repo='${urlField.text}', branch='${branchField.text}')")
            // Disable while the background sync runs — with the timeout in the
            // service this can't stay stuck, but avoid stacking second clicks.
            isEnabled = false
            val startedAt = System.currentTimeMillis()

            /**
             * Updates the label from any thread. ModalityState.any() is
             * essential: this is a modal dialog, and runnables submitted
             * without modality state default to NON_MODAL, which the IDE's
             * event queue refuses to dispatch while a modal dialog is up —
             * the result was a frozen "Syncing…" with zero feedback.
             */
            fun ui(update: () -> Unit) {
                com.intellij.openapi.application.ApplicationManager.getApplication()
                    .invokeLater(update, com.intellij.openapi.application.ModalityState.any())
            }

            // Background thread — never git on the EDT.
            com.intellij.util.concurrency.AppExecutorUtil
                .getAppExecutorService().submit {
                    val branch = branchField.text.trim().ifEmpty { "main" }
                    val result = try {
                        val snap = InstructionsRepoService.getInstance(project)
                            .syncNowFor(RepoSpec.parse(urlField.text)!!, branch) { stage ->
                                val secs = (System.currentTimeMillis() - startedAt) / 1000
                                dev.tellforgeai.llm.InstructionsSyncLog.info("integrations dialog stage: $stage")
                                ui {
                                    testLabel.text = "<html><body style='margin:0'><b>${secs}s</b> — ${esc(stage)}…</body></html>"
                                }
                            }
                        when {
                            snap.error.isNotEmpty() ->
                                "Error: ${esc(snap.error)}" +
                                    (if (snap.text.isNotEmpty()) "<br><br><b>Instructions loaded anyway</b>." else "")
                            snap.note.isNotEmpty() ->
                                "${esc(snap.note)}.<br>Synced ${snap.fileCount} markdown file(s) from <b>${esc(branch)}</b>." +
                                    "<br>Status will appear in the next system prompt build."
                            else ->
                                "Synced ${snap.fileCount} markdown file(s) from <b>${esc(branch)}</b>." +
                                    "<br>Status will appear in the next system prompt build."
                        }
                    } catch (e: Exception) {
                        "Error: ${esc(e.message ?: e.javaClass.simpleName)}"
                    }
                    val total = (System.currentTimeMillis() - startedAt) / 1000
                    com.intellij.openapi.diagnostic.Logger
                        .getInstance(ConfigureIntegrationsDialog::class.java)
                        .info("test sync finished in ${total}s: ${result.replace("<[^>]*>".toRegex(), " ").trim()}")
                    dev.tellforgeai.llm.InstructionsSyncLog
                        .mark("integrations dialog test finished after ${total}s")
                    ui {
                        testLabel.text = "<html><body style='margin:0'>after ${total}s — $result</body></html>"
                        isEnabled = true
                    }
                }
        }
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    override fun apply() {
        state.setRepoUrl(urlField.text)
        state.setBranch(branchField.text)
        // Both flags can only stick with a parseable, enabled configuration.
        state.setEnabled(enable.isSelected && state.configured())
        state.setWriteEnabled(write.isSelected && state.enabled())
    }
}

// ----------------------------------------------------------------------
// Sentry tab
// ----------------------------------------------------------------------

private class SentryTab(private val project: Project) : IntegrationTab {

    private val state = SentryState.getInstance(project)

    override val title = "Sentry"
    override val info = "Connect Sentry so the agent can investigate errors. Your application pushes " +
        "logs/errors to Sentry with its own SDK; the agent queries those errors with read-only tools and " +
        "explains what went wrong. This never mutates Sentry."

    private val enable = JBCheckBox("Enable Sentry tools for this project", state.enabled())
    private val orgField = JTextField(state.org(), 34)
    private val projectField = JTextField(state.project(), 34)
    private val baseUrlField = JTextField(state.baseUrl(), 34)
    private val tokenField = JBPasswordField().apply {
        state.token(project)?.let { text = it }
    }
    private val testLabel = JLabel(" ")

    override val panel: JComponent = FormBuilder.createFormBuilder()
        .addComponent(infoBanner(info), 4)
        .addComponent(enable, 4)
        .addLabeledComponent("Organization slug:", orgField)
        .addLabeledComponent("Project slug (optional; empty = all projects):", projectField)
        .addLabeledComponent("Base URL:", baseUrlField)
        .addLabeledComponent("Auth token:", tokenField)
        .addComponent(testButton(), 4)
        .addComponent(testLabel, 4)
        .panel

    private fun testButton() = JButton("Test connection").apply {
        addActionListener {
            val token = String(tokenField.password).trim()
            val org = orgField.text.trim()
            if (org.isEmpty()) {
                testLabel.text = "<html><font color='red'>Enter the organization slug first.</font></html>"
                return@addActionListener
            }
            testLabel.text = "Testing…"
            val base = baseUrlField.text.trim()
            // Never hit the network on the EDT.
            com.intellij.util.concurrency.AppExecutorUtil
                .getAppExecutorService().submit {
                    val result = SentryTool.testConnection(base, token, org)
                    com.intellij.openapi.application.ApplicationManager.getApplication()
                        .invokeLater({
                            testLabel.text = result
                        }, com.intellij.openapi.application.ModalityState.any())
                }
        }
    }

    override fun apply() {
        state.setEnabled(enable.isSelected)
        state.setOrg(orgField.text)
        state.setProject(projectField.text)
        state.setBaseUrl(baseUrlField.text)
        val token = String(tokenField.password).trim()
        if (token.isEmpty()) {
            state.setToken(project, "")
        } else {
            state.setToken(project, token)
        }
    }
}
