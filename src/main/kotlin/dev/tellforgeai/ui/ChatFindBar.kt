package dev.tellforgeai.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.JEditorPane
import javax.swing.SwingConstants
import javax.swing.event.DocumentListener
import javax.swing.text.DefaultHighlighter
import javax.swing.text.Element
import javax.swing.text.Highlighter

/**
 * Collapsible find bar for the chat transcript.
 *
 * Searches the plain text of an HTML [JEditorPane] so HTML markup (e.g. tags)
 * never match; matches reflect the same character offsets the transcript pane
 * itself uses for highlighting (ordinary `select(idx, idx)` ranges). Navigates
 * forward/backward with wrapping and shows a "n/m" counter.
 *
 * Keyboard behaviour (while this window / this bar has focus):
 *
 * - Cmd/Ctrl+F: opens the bar   (installed on [ChatPanel], not here)
 * - Enter / Cmd/Ctrl+G    next match
 * - Shift+Enter / Shift+Cmd/Ctrl+G previous match
 * - Escape: closes the bar
 */
internal class ChatFindBar(
    private val transcriptPane: JEditorPane,
    private val scrollPane: JScrollPane,
    private val onClose: () -> Unit,
) : JPanel(BorderLayout()) {

    /** Plain swing text field: no placeholder, tab-focus behaviour is predictable. */
    private val searchField = JTextField().apply {
        toolTipText = "Search in chat — Enter/Cmd+G next, Shift+Enter/Shift+Cmd+G previous, Escape closes"
        putClientProperty("JComponent.variant", "search-field")
    }

    private val countLabel = JLabel("").apply {
        horizontalAlignment = SwingConstants.RIGHT
        font = font.deriveFont(font.size2D - 1f)
    }

    private val prevButton = JButton("↑").apply {
        isFocusable = false
        toolTipText = "Previous match (Shift+Enter)"
        margin = JBUI.insets(2, 6)
    }
    private val nextButton = JButton("↓").apply {
        isFocusable = false
        toolTipText = "Next match (Enter)"
        margin = JBUI.insets(2, 6)
    }
    private val closeButton = JButton("✕").apply {
        isFocusable = false
        toolTipText = "Close (Escape)"
        margin = JBUI.insets(2, 6)
    }

    private val nextAction = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) = next()
    }
    private val prevAction = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) = previous()
    }
    private val closeAction = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) = close()
    }

    /** Debounced re-scan so bursts of keystrokes don't re-scan a large HTML doc. */
    private val searchTimer = Timer(150) { rescan() }.apply { isRepeats = false }

    /** Case-insensitive match spans in document coordinates, ascending. */
    private var matches: List<IntRange> = emptyList()

    /** Currently highlighted index; -1 = none yet. */
    private var currentIndex = -1

    /** Last query actually scanned; used by [invalidateMatches] to re-run. */
    private var lastQuery = ""

    /**
     * Tint painted over every match and a stronger fill over the current one,
     * drawn via the pane's Highlighter so it renders even when the transcript
     * has no keyboard focus (the native "selection" would be nearly invisible).
     */
    private val allMatchPainter = DefaultHighlighter.DefaultHighlightPainter(
        com.intellij.ui.JBColor.namedColor("TellForgeAI.findMatch", Color(0x66FFEB80, true))
    )
    private val currentMatchPainter = DefaultHighlighter.DefaultHighlightPainter(
        com.intellij.ui.JBColor.namedColor("TellForgeAI.findCurrentMatch", Color(0xFFFFD900.toInt(), true))
    )
    private val highlightTags = mutableListOf<Any>()

    init {
        border = JBUI.Borders.empty(4, 8, 4, 4)
        val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(prevButton)
            add(nextButton)
            add(closeButton)
        }
        val right = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(countLabel, BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }
        add(searchField, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)
        isVisible = false
        // Ensure the editor actually has a highlighter we can add tags to
        // (JEditorPane installs a BasicHighlighter by default).
        if (transcriptPane.highlighter == null) transcriptPane.highlighter = DefaultHighlighter()

        prevButton.addActionListener { previous() }
        nextButton.addActionListener { next() }
        closeButton.addActionListener { close() }

        // Live search as the user types (debounced). Explicit navigation = true.
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) { scheduleRescan() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) { scheduleRescan() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) { scheduleRescan() }
        })

        // Field-local keyboard behaviour.
        val im = searchField.inputMap
        val am = searchField.actionMap
        im.put(KeyStroke.getKeyStroke("ENTER"), "findNext")
        am.put("findNext", nextAction)
        im.put(KeyStroke.getKeyStroke("shift ENTER"), "findPrev")
        am.put("findPrev", prevAction)
        im.put(KeyStroke.getKeyStroke("meta G"), "findNext")
        am.put("findNext", nextAction)
        im.put(KeyStroke.getKeyStroke("control G"), "findNext")
        im.put(KeyStroke.getKeyStroke("shift meta G"), "findPrev")
        im.put(KeyStroke.getKeyStroke("control shift G"), "findPrev")
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "findClose")
        am.put("findClose", closeAction)
    }

    /** Shows the bar and focuses the field, preserving the previous query. */
    fun open() {
        isVisible = true
        revalidate()
        repaint()
        searchField.requestFocusInWindow()
        searchField.selectAll()
        if (searchField.text.isNotEmpty()) rescan()
    }

    fun close() {
        if (!isVisible) return
        isVisible = false
        matches = emptyList()
        currentIndex = -1
        lastQuery = ""
        countLabel.text = ""
        clearHighlights()
        onClose()
    }

    /**
     * Steps [delta] matches forward (wrapping), highlighting and scrolling to
     * it. A no-op when the query is empty or produced no matches.
     */
    fun move(delta: Int) {
        if (matches.isEmpty()) return
        currentIndex = if (currentIndex < 0) {
            // First navigation on a fresh search: step into the list from the
            // nearest end, wrapping the other way round the start.
            if (delta > 0) 0 else matches.size - 1
        } else {
            (currentIndex + delta + matches.size) % matches.size
        }
        highlight(currentIndex)
        matches.getOrNull(currentIndex)?.let { navigateToMatch(it) }
    }

    fun next() = move(+1)

    fun previous() = move(-1)

    /**
     * Transient tail/finished-block updates shift document offsets. Rather than
     * tracking exactly which region was touched, re-run the scan (cheap for
     * chat-sized documents). Navigation is suppressed: a streaming repaint must
     * silently re-anchor the highlights, not jump the selection/viewport.
     */
    fun invalidateMatches() {
        if (!isVisible) return
        if (lastQuery.isNotEmpty()) rescan(navigate = false) else {
            matches = emptyList()
            currentIndex = -1
            clearHighlights()
        }
    }

    private fun scheduleRescan() {
        searchTimer.restart()
    }

    /**
     * [navigate] = user-triggered scan (typing a new query): jump to the first
     * match. Streaming refreshes pass false so nothing moves/steals focus.
     */
    private fun rescan(navigate: Boolean = true) {
        val query = searchField.text
        lastQuery = query
        // Keep the user's position: map the current index through the re-scan.
        val prevIndex = currentIndex
        val prevCount = matches.size
        matches = if (query.isEmpty()) emptyList() else computeMatches(query)
        clearHighlights()
        for (range in matches) addHighlightTag(range, allMatchPainter)
        if (navigate) {
            currentIndex = -1
            updateCountLabel()
            if (matches.isNotEmpty()) next()
        } else {
            // Re-run the scan only re-hosts tags; keep the same ordinal position
            // (clamped if the match count shrank) and re-paint the current fill.
            currentIndex = if (prevCount > 0) prevIndex.coerceIn(0, matches.size - 1) else -1
            updateCountLabel()
            if (currentIndex >= 0) addHighlightTag(matches[currentIndex], currentMatchPainter)
        }
    }

    /** Soft tints/fills painted on the transcript; they persist until cleared. */
    private fun addHighlightTag(range: IntRange, painter: DefaultHighlighter.DefaultHighlightPainter) {
        try {
            transcriptPane.highlighter.addHighlight(range.first, range.last + 1, painter)?.let {
                highlightTags.add(it)
            }
        } catch (e: Exception) {
            // Document changed between scan and paint: skip this stale span.
        }
    }

    private fun clearHighlights() {
        for (tag in highlightTags) {
            try {
                transcriptPane.highlighter.removeHighlight(tag)
            } catch (e: Exception) {
                // Tag already gone (e.g. document rebuild) — nothing to do.
            }
        }
        highlightTags.clear()
    }

    private fun updateCountLabel() {
        countLabel.text = when {
            searchField.text.isEmpty() -> ""
            matches.isEmpty() -> "No matches"
            else -> "${currentIndex + 1}/${matches.size}"
        }
    }

    /**
     * When a *new* match becomes current (user navigation or a fresh query),
     * remove the old current-match fill so only one is filled.
     */
    private fun highlight(index: Int) {
        updateCountLabel()
        val range = matches.getOrNull(index) ?: return
        while (highlightTags.size > matches.size) {
            try {
                transcriptPane.highlighter.removeHighlight(highlightTags.removeAt(highlightTags.size - 1))
            } catch (e: Exception) {
            }
        }
        addHighlightTag(range, currentMatchPainter)
    }

    /**
     * Selects the match and scrolls it into view (explicit navigation only).
     *
     * The pane's selection is deliberately NOT used: DefaultHighlighter paints
     * the selection band OVER highlight tags, which would cover our yellow
     * current-match fill. Instead we scroll to the match and rely solely on
     * the [currentMatchPainter] tag (opaque yellow) to mark it — the opaque
     * yellow is visible over any content, with or without focus.
     */
    private fun navigateToMatch(range: IntRange) {
        try {
            val rect = transcriptPane.modelToView2D(range.first)?.bounds ?: return
            val viewport = scrollPane.viewport ?: return
            val visible = viewport.viewRect
            val centerY = rect.y + rect.height / 2
            // Center the match when it's outside the already-visible portion.
            if (centerY < visible.y || centerY > visible.y + visible.height) {
                viewport.viewPosition = java.awt.Point(
                    viewport.viewPosition.x,
                    (centerY - visible.height / 2).coerceAtLeast(0),
                )
            }
        } catch (e: Exception) {
            // Layout may not have settled for a just-inserted block; ignore.
        }
    }
    /**
     * Case-insensitive scan over the document's leaf text (skips markup).
     * Returns document-relative ranges usable with [JEditorPane.select].
     */
    private fun computeMatches(query: String): List<IntRange> {
        val doc = transcriptPane.document ?: return emptyList()
        val leaves = mutableListOf<LeafText>()
        collectLeaves(doc.defaultRootElement, leaves)
        val results = mutableListOf<IntRange>()
        val lowerQuery = query.lowercase()
        for (leaf in leaves) {
            val text = leaf.text.lowercase()
            var at = 0
            while (true) {
                val found = text.indexOf(lowerQuery, at)
                if (found < 0) break
                results.add(leaf.start + found until leaf.start + found + lowerQuery.length)
                at = found + lowerQuery.length
            }
        }
        return results
    }

    private data class LeafText(val start: Int, val text: String)

    /** Ordered leaf text elements with their document-relative start offsets. */
    private fun collectLeaves(element: Element, out: MutableList<LeafText>) {
        if (element.isLeaf) {
            try {
                val start = element.startOffset
                val end = element.endOffset
                if (end > start) {
                    out.add(LeafText(start, element.document.getText(start, end - start)))
                }
            } catch (e: Exception) {
                // Detached/degenerate element during a concurrent document edit: skip.
            }
            return
        }
        for (i in 0 until element.elementCount) {
            collectLeaves(element.getElement(i), out)
        }
    }
}
