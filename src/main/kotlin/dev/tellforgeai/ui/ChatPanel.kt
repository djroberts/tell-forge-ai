package dev.tellforgeai.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBList
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.tellforgeai.llm.ChatMessage
import dev.tellforgeai.llm.ContextGuard
import dev.tellforgeai.llm.HistoryTrimmer
import dev.tellforgeai.llm.LlmClient
import dev.tellforgeai.llm.ModelInfoLookup
import dev.tellforgeai.llm.ProjectContext
import dev.tellforgeai.llm.Prompts
import dev.tellforgeai.llm.ToolCall
import dev.tellforgeai.model.ChatSession
import dev.tellforgeai.model.ProviderProfile
import dev.tellforgeai.settings.TellForgeAIChatsState
import dev.tellforgeai.settings.TellForgeAIConfigurable
import dev.tellforgeai.settings.TellForgeAIProjectState
import dev.tellforgeai.settings.TellForgeAISettings
import dev.tellforgeai.tools.ToolRegistry
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.Image
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultEditorKit
import javax.swing.text.DefaultCaret
import javax.swing.text.Element
import javax.swing.text.StyleConstants
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit
import javax.swing.KeyStroke
import java.awt.event.KeyEvent
import java.awt.datatransfer.DataFlavor
import java.security.MessageDigest
import java.awt.datatransfer.Transferable

class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val LOG = Logger.getInstance(ChatPanel::class.java)

    enum class Mode(val label: String) { PLAN("Foretell"), ACT("Forge") }

    private val client = LlmClient()
    private val history = mutableListOf<ChatMessage>()
    private var mode: Mode = Mode.PLAN
    private var streaming = false
    /** Mode of the last successfully finished turn — drives the green "complete" status line. */
    private var completedMode: Mode? = null
    /** Tool rounds executed in the current user turn (loop guard). */
    private var toolRounds = 0
    /** Most recent provider-reported prompt usage, for the live context estimate. */
    private var lastPromptTokens = 0
    private var contextLookupModel = ""
    private var pricingLookupModel = ""
    private var modelInfoCacheKey = ""
    private var lastAssistantMarkdown = StringBuilder()
    /** Streaming reasoning/thinking text (transient — cleared once the answer starts). */
    private var lastReasoning = StringBuilder()
    private var currentChatId: String? = null
    private var thinkingDots = 0
    /** Last time the transcript was re-rendered (throttle for per-token streaming). */
    private var lastRepaintAt = 0L
    /** Cached project snapshot for the system prompt; refreshed at most every 60s. */
    private var cachedContext = ""
    private var contextFetchedAt = 0L
    /** Fires once per chat when trimming kicks in or the window is nearly full. */
    private var contextTrimNotified = false
    private val thinkingTimer: Timer = Timer(400) {
        thinkingDots = (thinkingDots + 1) % 4
        if (streaming && lastAssistantMarkdown.isEmpty()) {
            repaintTranscript()
        } else if (streaming && isNearBottom()) {
            // Belt-and-braces: large blocks can finish layout after the throttled
            // repaint's deferred scroll; re-pin at least 2.5x/second while streaming.
            scrollToBottom()
        }
    }.apply { isRepeats = true }

    // --- Top bar ---
    private val homeButton = JButton(AllIcons.Actions.Back)
    private val profileCombo = JComboBox<ProviderProfile>()
    private val planButton = JRadioButton(Mode.PLAN.label, true)
    private val actButton = JRadioButton(Mode.ACT.label)
    private val homePlanButton = JRadioButton(Mode.PLAN.label, true)
    private val homeActButton = JRadioButton(Mode.ACT.label)
    private val newChatButton = JButton(AllIcons.General.Add)
    private val settingsButton = JButton(AllIcons.General.Settings)
    private val testButton = JButton("Test")

    // --- Center / bottom ---
    private val cards = JPanel(CardLayout())
    /** Finished transcript: blocks inserted incrementally, never reparsed wholesale. */
    private val transcriptPane = JEditorPane()
    /** Transient tail (streaming bubble / status lines) — re-rendered per tick, cheap. */
    private val tailPane = JEditorPane()
    private val chatPane: JEditorPane = transcriptPane // kept as the main pane alias
    /** Scroll pane wrapping the transcript; accessed for auto-scroll-to-bottom. */
    private val chatScroll = JBScrollPane(
        tailStackPanel(),
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
    )

    private val inputArea = JTextArea(3, 40)
    private val sendStopButton = JButton("Send")
    /** Sticky header: shows the first user message of the current chat. */
    private val firstMessageLabel = JLabel().apply {
        font = font.deriveFont(java.awt.Font.BOLD)
        verticalAlignment = SwingConstants.TOP
    }
    private val contextBar = JProgressBar(0, 1000).apply {
        isStringPainted = false
        preferredSize = Dimension(140, 8)
    }
    private val contextLabel = JLabel("")
    private val usageLabel = JLabel("")
    private val modelInfoLabel = JLabel("Select a provider to see model pricing")
    private val chatModelInfoLabel = JLabel("")
    /** Re-fetches pricing for the selected model; spins while the lookup runs. */
    private val refreshPricingButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh model pricing"
        isFocusable = false
        putClientProperty("JButton.buttonType", "toolBarButton")
    }
    private val refreshIconIdle = AllIcons.Actions.Refresh
    private val refreshIconSpin = com.intellij.ui.AnimatedIcon.Default()
    /** Right side of the sticky header: context meter and this chat's cumulative token/cost totals. */
    private val contextMeter = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
        add(contextBar)
        add(contextLabel)
        add(usageLabel)
    }
    private val stickyHeader = JPanel(BorderLayout(8, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 12, 6, 12)
        val stickyContent = JPanel(BorderLayout(4, 2)).apply {
            isOpaque = false
            add(firstMessageLabel, BorderLayout.CENTER)
            add(chatModelInfoLabel, BorderLayout.SOUTH)
        }
        add(stickyContent, BorderLayout.CENTER)
        add(contextMeter, BorderLayout.EAST)
    }
    /** Status line ("N images attached", Clear button) on top, thumbnails row below.
     *  Thumbnails are capped at [thumbMaxSize] px so the strip stays one short row. */
    private class ImageStrip : JPanel(BorderLayout()) {
        val statusLabel = JLabel("", SwingConstants.LEFT).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = UIUtil.getLabelDisabledForeground()
        }
        val clearButton = JButton("Clear").apply {
            font = font.deriveFont(font.size2D - 1f)
            margin = JBUI.insets(0, 4)
            isContentAreaFilled = false
            toolTipText = "Remove all pasted images"
        }
        private val thumbs = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2))
        /** Horizontal scroll: many images scroll sideways instead of stretching. */
        val thumbScroll = JBScrollPane(thumbs).apply {
            border = null
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
            verticalScrollBar.preferredSize = Dimension(0, 0)
        }

        init {
            val status = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
                isOpaque = false
                add(statusLabel)
                add(clearButton)
            }
            add(status, BorderLayout.NORTH)
            add(thumbScroll, BorderLayout.CENTER)
        }

        fun setThumbs(components: List<Component>) {
            thumbs.removeAll()
            components.forEach(thumbs::add)
            thumbs.revalidate()
            thumbs.repaint()
        }
    }

    /** Max dimension (px) of the in-strip thumbnails — small so the strip stays one short row. */
    private val thumbMaxSize = 56
    /** Toggles whether command execution needs user approval; persisted in settings.
     *  Two instances are created (chat card + home card) and kept in sync, since a
     *  Swing component can only belong to one container. */
    private val askPermissionCheckBox: JCheckBox = JCheckBox("Ask permission to run commands").apply {
        toolTipText = "When unchecked, TellForgeAI runs commands without asking first"
        addActionListener { evt ->
            val src = evt.source as JCheckBox
            TellForgeAISettings.getInstance().setAskBeforeRunCommands(src.isSelected)
            homeAskPermissionCheckBox.isSelected = src.isSelected
        }
        isSelected = TellForgeAISettings.getInstance().askBeforeRunCommands()
    }
    private val homeAskPermissionCheckBox: JCheckBox = JCheckBox("Ask permission to run commands").apply {
        toolTipText = "When unchecked, TellForgeAI runs commands without asking first"
        addActionListener { evt ->
            val src = evt.source as JCheckBox
            TellForgeAISettings.getInstance().setAskBeforeRunCommands(src.isSelected)
            askPermissionCheckBox.isSelected = src.isSelected
        }
        isSelected = TellForgeAISettings.getInstance().askBeforeRunCommands()
    }
    /**
     * HTML for all finished (non-streaming) transcript blocks, one entry per block.
     * Rendering is incremental: each block is inserted into the document once, and
     * only the streaming "tail" (in-progress bubble / status line) is re-rendered.
     * The previous approach re-parsed and re-laid-out the ENTIRE transcript on every
     * tool result and token tick — O(n²) over a long tool loop, pegging the EDT.
     */
    private val finishedBlocks = mutableListOf<String>()
    private var streamingHeader = ""
    private var userBubbleBg: Color = Color(0xE1ECFA)
    private var assistantBubbleBg: Color = Color(0xF4F4F4)

    // --- Chat list cards ---
    private val cardBg = JBColor(Color(0xFFFFFF), Color(0x3C3F41))
    private val cardHover = JBColor(Color(0xF0F4F8), Color(0x454A4F))
    private val cardLine = JBColor(Color(0xE0E4E8), Color(0x4E5254))
    private var hoveredChatIndex = -1

    // --- Image paste ---
    /** Max dimension (px) of the longest side for pasted images — kept small so
     *  persisted chats stay compact and vision tokens stay cheap. */
    private val imageMaxSize = 256
    /** Base64 PNG data URLs of images pasted into the input, not yet sent. */
    private val pendingImages = mutableListOf<String>()
    /** SHA-256 hashes of the downscaled PNGs in [pendingImages] — used to ignore duplicates. */
    private val pendingImageHashes = mutableSetOf<String>()
    /** Thumbnail strips above the input areas showing pending pasted images. */
    private val imageStrip = newImageStrip()
    private val homeImageStrip = newImageStrip()
    /** All strips mirror the same pendingImages list (chat card + home card). */
    private val imageStrips = listOf(imageStrip, homeImageStrip)

    private fun newImageStrip() = ImageStrip().apply {
        clearButton.addActionListener {
            pendingImages.clear()
            pendingImageHashes.clear()
            refreshImageStrip()
        }
    }

    // --- Home screen ---
    private val chatListModel = DefaultListModel<ChatSession>()
    private val chatList = JBList(chatListModel)
    private val homeInputArea = JTextArea(3, 40)

    init {
        Disposer.register(TellForgeAIToolWindowDisposable.get(project)) { client.cancel() }

        homeButton.toolTipText = "Back to all chats"
        homeButton.addActionListener { showHome() }
        newChatButton.toolTipText = "New chat"
        newChatButton.addActionListener { newChat() }
        profileCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ) = super.getListCellRendererComponent(
                list,
                (value as? ProviderProfile)?.let { "${it.name}  ·  ${it.model}" } ?: "",
                index, selected, focused,
            )
        }
        profileCombo.addActionListener(ActionListener { onProfileSelected() })
        // Re-sync from persisted settings every time the dropdown opens, so
        // providers added/edited in the settings page show up immediately.
        profileCombo.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent) =
                syncProfilesFromSettings()
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent) = Unit
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent) = Unit
        })
        planButton.addActionListener { setMode(Mode.PLAN) }
        actButton.addActionListener { setMode(Mode.ACT) }
        homePlanButton.addActionListener { setMode(Mode.PLAN) }
        homeActButton.addActionListener { setMode(Mode.ACT) }
        settingsButton.toolTipText = "Configure TellForgeAI providers"
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, TellForgeAIConfigurable::class.java) { refreshProfiles() }
        }
        testButton.toolTipText = "Test the selected provider connection (/models)"
        testButton.addActionListener { onTestConnection() }
        refreshPricingButton.addActionListener { onRefreshPricing() }
        ButtonGroup().apply { add(planButton); add(actButton) }

        val top = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(8, 10)
            add(homeButton, BorderLayout.WEST)
            add(profileCombo, BorderLayout.CENTER)
            val right = JPanel().apply {
                isOpaque = false
                add(newChatButton); add(testButton); add(settingsButton)
            }
            add(right, BorderLayout.EAST)
        }

        chatPane.isEditable = false
        chatPane.contentType = "text/html"
        (transcriptPane.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        val kit = chatPane.editorKit as HTMLEditorKit
        val css = kit.styleSheet
        tailPane.isEditable = false
        tailPane.contentType = "text/html"
        // Same stylesheet as the transcript, but a separate kit instance (an
        // HTMLEditorKit instance should back one pane's document).
        val tailKit = HTMLEditorKit().apply { styleSheet = css }
        tailPane.editorKit = tailKit
        (tailPane.caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        val dark = UIUtil.isUnderDarcula()
        userBubbleBg = if (dark) Color(0x2B4A66) else Color(0xE1ECFA)
        assistantBubbleBg = if (dark) Color(0x3C3F41) else Color(0xF4F4F4)
        css.addRule("body { font-family: ${UIUtil.getLabelFont().fontName}; font-size: ${UIUtil.getLabelFont().size}pt; margin: 12px; }")
        css.addRule("pre { background: ${hex(if (dark) Color(0x2B2B2B) else Color(0xEEEEEE))}; padding: 8px; }")
        css.addRule(".who { font-weight: bold; margin-bottom: 3px; }")
        css.addRule(".mode { color: #888; font-size: 0.85em; }")
        css.addRule("hr { border: 0; height: 1px; background: ${hex(if (dark) Color(0x4E5254) else Color(0xE0E0E0))}; }")
        val center = chatScroll
        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.border = JBUI.Borders.empty(8)
        sendStopButton.margin = JBUI.insets(6, 16)
        inputArea.toolTipText = ENTER_TO_SEND_TIP
        installEnterToSend(inputArea) { send() }
        sendStopButton.addActionListener { if (streaming) stop() else send() }
        val sendColumn = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(sendStopButton, BorderLayout.NORTH)
            val radios = JPanel().apply {
                isOpaque = false
                add(planButton); add(actButton)
            }
            add(radios, BorderLayout.SOUTH)
            add(askPermissionCheckBox, BorderLayout.CENTER)
        }
        val bottom = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(6, 12, 12, 12)
            add(JBScrollPane(inputArea), BorderLayout.CENTER)
            add(sendColumn, BorderLayout.EAST)
        }
        // Pasted-image thumbnails sit above the input; image strip is shared with the home card.
        val bottomWithImages = JPanel(BorderLayout()).apply {
            add(imageStrip, BorderLayout.NORTH)
            add(bottom, BorderLayout.CENTER)
        }
        installImagePaste(inputArea)

        // --- Home card: list of saved chats + input that starts a new chat ---
        chatList.emptyText.text = "No chats yet — type a message below to start one"
        chatList.cellRenderer = object : JPanel(BorderLayout(JBUI.scale(8), 0)), ListCellRenderer<Any> {
            val iconLabel = JLabel(AllIcons.FileTypes.Text)
            val titleLabel = JLabel()
            val timeLabel = JLabel().apply {
                font = font.deriveFont(font.size2D - 1f)
                foreground = UIUtil.getLabelDisabledForeground()
            }
            val delLabel = JLabel(AllIcons.Actions.GC).apply {
                toolTipText = "Delete chat"
            }
            val textPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(titleLabel, BorderLayout.CENTER)
                add(timeLabel, BorderLayout.SOUTH)
            }
            val card = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = true
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(cardLine),
                    JBUI.Borders.empty(10, 12),
                )
                add(iconLabel, BorderLayout.WEST)
                add(textPanel, BorderLayout.CENTER)
                add(delLabel, BorderLayout.EAST)
            }
            val outer = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8)
                add(card, BorderLayout.CENTER)
            }

            init {
                outer.background = card.background
            }

            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, selected: Boolean, focused: Boolean,
            ): Component {
                val chat = value as? ChatSession
                titleLabel.text = chat?.title ?: ""
                titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD)
                timeLabel.text = chat?.let { relativeTime(it.updatedAt) } ?: ""
                val hovered = index == hoveredChatIndex && !selected
                val bg = when {
                    selected -> UIUtil.getListSelectionBackground(true)
                    hovered -> cardHover
                    else -> cardBg
                }
                card.background = bg
                delLabel.isVisible = selected || hovered
                return outer
            }
        }
        chatList.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val idx = chatList.locationToIndex(e.point)
                if (idx != hoveredChatIndex) {
                    hoveredChatIndex = idx
                    chatList.repaint()
                }
            }
        })
        chatList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                if (hoveredChatIndex != -1) {
                    hoveredChatIndex = -1
                    chatList.repaint()
                }
            }
        })
        chatList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = chatList.locationToIndex(e.point)
                if (idx < 0) return
                chatList.selectedIndex = idx
                val chat = chatListModel.get(idx)
                val r = chatList.getCellBounds(idx, idx)
                // Click on the trash icon (right edge of the row) deletes; anywhere else opens.
                if (e.x >= r.x + r.width - JBUI.scale(42)) deleteChat(chat) else openChat(chat.id)
            }
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
            private fun maybeShowPopup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val idx = chatList.locationToIndex(e.point)
                if (idx >= 0) chatList.selectedIndex = idx
                JPopupMenu().apply {
                    add("Delete chat").also { item ->
                        item.addActionListener { deleteSelectedChat() }
                        add(item)
                    }
                    show(e.component, e.x, e.y)
                }
            }
        })
        val homeScroll = JBScrollPane(
            chatList,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
        )
        homeInputArea.lineWrap = true
        homeInputArea.wrapStyleWord = true
        homeInputArea.border = JBUI.Borders.empty(8)
        homeInputArea.toolTipText = ENTER_TO_SEND_TIP
        installEnterToSend(homeInputArea) { sendNew() }
        installImagePaste(homeInputArea)
        val homeSendButton = JButton("Start chat")
        homeSendButton.margin = JBUI.insets(6, 16)
        homeSendButton.toolTipText = "Start a new chat with this message"
        homeSendButton.addActionListener { sendNew() }
        ButtonGroup().apply { add(homePlanButton); add(homeActButton) }
        val homeSendColumn = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            add(homeSendButton, BorderLayout.NORTH)
            add(homeAskPermissionCheckBox, BorderLayout.CENTER)
            val radios = JPanel().apply {
                isOpaque = false
                add(homePlanButton); add(homeActButton)
            }
            add(radios, BorderLayout.SOUTH)
        }
        val homeBottom = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(6, 12, 12, 12)
            add(JBScrollPane(homeInputArea), BorderLayout.CENTER)
            add(homeSendColumn, BorderLayout.EAST)
        }
        // Same pending-image feedback as the chat card, on the home screen too.
        val homeBottomWithImages = JPanel(BorderLayout()).apply {
            add(homeImageStrip, BorderLayout.NORTH)
            add(homeBottom, BorderLayout.CENTER)
        }
        val homeHeader = JPanel(BorderLayout(6, 4)).apply {
            border = JBUI.Borders.empty(12, 12, 4, 12)
            add(JLabel("Chats").apply {
                font = font.deriveFont(font.size2D + 3f).deriveFont(java.awt.Font.BOLD)
            }, BorderLayout.NORTH)
        }
        val modelInfoRow = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(modelInfoLabel, BorderLayout.CENTER)
            add(refreshPricingButton, BorderLayout.EAST)
        }
        homeHeader.add(modelInfoRow, BorderLayout.SOUTH)
        chatList.border = JBUI.Borders.empty(0, 6, 6, 6)
        val homeCard = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
            add(homeHeader, BorderLayout.NORTH)
            add(homeScroll, BorderLayout.CENTER)
            add(homeBottomWithImages, BorderLayout.SOUTH)
        }
        val chatCard = JPanel(BorderLayout()).apply {
            add(stickyHeader, BorderLayout.NORTH)
            add(center, BorderLayout.CENTER)
            add(bottomWithImages, BorderLayout.SOUTH)
        }

        cards.add(homeCard, CARD_HOME)
        cards.add(chatCard, CARD_CHAT)
        add(top, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)
        preferredSize = Dimension(480, 600)

        refreshProfiles()
        showHome()
        (profileCombo.selectedItem as? ProviderProfile)?.let { ensureModelInfo(it) }
    }

    // --- navigation ---

    private fun showCard(name: String) {
        (cards.layout as CardLayout).show(cards, name)
    }

    /** Shows the home screen listing all chats; the default view when no chat is chosen. */
    fun showHome() {
        if (streaming) client.cancel()
        setStreaming(false)
        currentChatId = null
        contextLookupModel = ""
        pricingLookupModel = ""
        history.clear()
        finishedBlocks.clear()
        renderedBlocks = 0
        lastAssistantMarkdown = StringBuilder()
        lastReasoning = StringBuilder()
        streamingHeader = ""
        refreshChatList()
        showCard(CARD_HOME)
    }

    private fun refreshChatList() {
        val selectedId = chatList.selectedValue?.id
        chatListModel.clear()
        TellForgeAIChatsState.getInstance(project).all().forEach { chatListModel.addElement(it) }
        if (selectedId != null) {
            chatList.selectedIndex = (0 until chatListModel.size())
                .indexOfFirst { chatListModel[it].id == selectedId }
                .takeIf { it >= 0 } ?: -1
        }
    }

    private fun deleteSelectedChat() {
        val chat = chatList.selectedValue ?: return
        deleteChat(chat)
    }

    private fun deleteChat(chat: ChatSession) {
        val answer = Messages.showYesNoDialog(
            project, "Delete chat \"${chat.title}\"?", "TellForgeAI", Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) {
            TellForgeAIChatsState.getInstance(project).delete(chat.id)
            refreshChatList()
        }
    }

    /** Opens an existing chat and renders its stored history. */
    private fun openChat(chatId: String) {
        val chat = TellForgeAIChatsState.getInstance(project).get(chatId) ?: return
        currentChatId = chat.id
        completedMode = null
        contextLookupModel = ""
        pricingLookupModel = ""
        contextTrimNotified = false
        chatModelInfoLabel.text = modelInfoLabel.text
        chatModelInfoLabel.toolTipText = modelInfoLabel.toolTipText
        history.clear()
        history.addAll(chat.messages)
        finishedBlocks.clear()
        renderedBlocks = 0
        lastAssistantMarkdown = StringBuilder()
        lastReasoning = StringBuilder()
        streamingHeader = ""
        for (m in chat.messages) {
            when (m.role) {
                "user" -> finishedBlocks.add(
                    bubble(
                        "You",
                        m.images.takeIf { it.isNotEmpty() }?.let { imgs ->
                            "<div style='margin:4px 0'>" + imgs.joinToString("") {
                                "<img src='$it' width='128' height='128' style='margin:2px'/>"
                            } + "</div>"
                        }.orEmpty() + esc(m.content).replace("\n", "<br/>"),
                        userBubbleBg,
                    )
                )
                "assistant" -> if (m.content.isNotBlank()) {
                    finishedBlocks.add(bubble("Assistant", markdownToHtml(m.content), assistantBubbleBg))
                } else if (m.toolCalls.isNotEmpty()) {
                    m.toolCalls.forEach { appendToolResult(it, "(from earlier session)") }
                }
                "tool" -> { /* tool results are folded into the following turn; skip */ }
            }
        }
        rebuildTranscript()
        (profileCombo.selectedItem as? ProviderProfile)?.let { ensureModelInfo(it) }
        updateStickyHeader()
        showCard(CARD_CHAT)
    }

    private fun newChat() {
        if (streaming) client.cancel()
        setStreaming(false)
        currentChatId = null
        completedMode = null
        contextLookupModel = ""
        pricingLookupModel = ""
        contextTrimNotified = false
        history.clear()
        finishedBlocks.clear()
        renderedBlocks = 0
        lastAssistantMarkdown = StringBuilder()
        lastReasoning = StringBuilder()
        streamingHeader = ""
        chatList.clearSelection()
        rebuildTranscript()
        inputArea.text = ""
        updateStickyHeader()
        showCard(CARD_CHAT)
        inputArea.requestFocusInWindow()
    }

    /** Send pressed on the home screen: starts a new chat with this message. */
    private fun sendNew() {
        val text = homeInputArea.text.trim()
        // pendingImages are shared with the chat input; allow an image-only start.
        if (text.isEmpty() && pendingImages.isEmpty()) return
        homeInputArea.text = ""
        newChat()
        inputArea.text = text
        send()
    }

    /** Persists the current in-memory history into the active chat session. */
    private fun persistChat() {
        val id = currentChatId ?: return
        val state = TellForgeAIChatsState.getInstance(project)
        val chat = state.get(id) ?: ChatSession.new("New chat").copy(id = id)
        chat.messages.clear()
        chat.messages.addAll(history)
        chat.updatedAt = System.currentTimeMillis()
        chat.title = chat.autoTitle()
        state.upsert(chat)
    }

    private fun relativeTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        val min = diff / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            min < 60 * 24 -> "${min / 60}h ago"
            else -> "${min / (60 * 24)}d ago"
        }
    }

    fun refreshProfiles() {
        syncProfilesFromSettings()
        if (profileCombo.itemCount == 0) {
            appendSystem("No providers configured. Click the gear icon to add an OpenAI-compatible provider (URL, key, model).")
        }
    }

    /** Reloads the combo items from [TellForgeAISettings], preserving the current selection when possible. */
    private fun syncProfilesFromSettings() {
        val profiles = TellForgeAISettings.getInstance().profiles()
        val currentItems = (0 until profileCombo.itemCount)
            .mapNotNull { profileCombo.getItemAt(it) as? ProviderProfile }
        if (profiles == currentItems) return // nothing changed; avoid churn + duplicate action events

        val previouslySelectedId = (profileCombo.selectedItem as? ProviderProfile)?.id
            ?: TellForgeAIProjectState.getInstance(project).selectedProfileId
        profileCombo.removeAllItems()
        profiles.forEach { profileCombo.addItem(it) }
        val select = profiles.firstOrNull { it.id == previouslySelectedId } ?: profiles.firstOrNull()
        if (select != null) profileCombo.selectedItem = select
    }

    private fun onProfileSelected() {
        val p = profileCombo.selectedItem as? ProviderProfile ?: return
        TellForgeAIProjectState.getInstance(project).selectedProfileId = p.id
        ensureModelInfo(p)
        updateStickyHeader()
    }

    /** Resolve context limits and public token pricing once per chat/model. */
    private fun ensureModelInfo(profile: ProviderProfile) {
        val model = profile.modelFor(mode == Mode.PLAN).trim()
        if (model.isBlank()) return
        val settings = TellForgeAISettings.getInstance()
        val aiEnabled = settings.aiPricingLookupEnabled()
        // Bump the cache namespace when lookup validation changes, so old unverified prices aren't reused.
        // pricing-v3: context priorities reworked (provider /models first, Stale-safe callbacks).
        val key = listOf("pricing-v3", profile.id, profile.trimmedBaseUrl(), model, settings.webSearchProvider().lowercase(), "ai=$aiEnabled").joinToString("|")
        modelInfoCacheKey = key
        val cached = settings.modelInfoCache(key)
        modelInfoLabel.text = cached?.let { formatModelInfo(profile, model, it.inputPricePerMillion, it.cachedInputPricePerMillion, it.outputPricePerMillion, it.status, it.checkedAt) }
            ?: "${profile.name} · $model · Fetching model pricing…"
        chatModelInfoLabel.text = if (currentChatId == null) "" else modelInfoLabel.text
        setPricingSpinning(statusIsFetching(cached))
        if (cached != null) {
            syncPricingIntoChat(model, cached.inputPricePerMillion, cached.cachedInputPricePerMillion, cached.outputPricePerMillion, cached.source, cached.checkedAt)
            updateModelInfoLabel(profile, model, cached)
        }
        if (contextLookupModel != key) {
            contextLookupModel = key
            client.fetchContextWindow(profile, model) { window ->
                if (window != null) ApplicationManager.getApplication().invokeLater {
                    // Ignore stale replies after the profile/model/mode changed:
                    // writing them would assign one model's window to another.
                    if (contextLookupModel != key) return@invokeLater
                    val current = TellForgeAISettings.getInstance().modelInfoCache(key) ?: TellForgeAISettings.getInstance().expiredModelInfoCache(key)
                    val entry = current ?: dev.tellforgeai.settings.ModelInfoCacheEntry().apply { this.key = key }
                    entry.contextWindow = window
                    entry.checkedAt = maxOf(entry.checkedAt, System.currentTimeMillis())
                    TellForgeAISettings.getInstance().putModelInfoCache(entry)
                    currentChatId?.let { TellForgeAIChatsState.getInstance(project).get(it) }?.let { chat ->
                        // Chat window is bound to this exact model only (ContextGuard
                        // re-checks the association when reading).
                        chat.contextWindow = window
                        chat.contextModel = model
                        TellForgeAIChatsState.getInstance(project).upsert(chat)
                    }
                    updateStickyHeader()
                }
            }
        }
        if (cached == null && pricingLookupModel != key) {
            pricingLookupModel = key
            setPricingSpinning(true)
            val expired = settings.expiredModelInfoCache(key)
            val entry = expired ?: dev.tellforgeai.settings.ModelInfoCacheEntry().apply { this.key = key }
            val aiEnabled = settings.aiPricingLookupEnabled()
            entry.status = "Fetching"
            settings.putModelInfoCache(entry)
            ModelInfoLookup.lookup(profile, model, aiEnabled) { info ->
                ApplicationManager.getApplication().invokeLater {
                    // Stale reply after a profile/model/mode switch: still cache
                    // it under its own key, but don't touch the UI or the current chat.
                    val stale = modelInfoCacheKey != key
                    val saved = dev.tellforgeai.settings.ModelInfoCacheEntry().apply {
                        this.key = key
                        contextWindow = info.contextWindow ?: entry.contextWindow
                        inputPricePerMillion = info.pricing?.inputPerMillion
                        cachedInputPricePerMillion = info.pricing?.cachedInputPerMillion
                        outputPricePerMillion = info.pricing?.outputPerMillion
                        source = info.pricing?.let { "${it.sourceTitle} — ${it.sourceUrl}" }.orEmpty()
                        checkedAt = System.currentTimeMillis()
                        status = info.status
                        lookupInputTokens = info.lookupInputTokens.toLong()
                        lookupOutputTokens = info.lookupOutputTokens.toLong()
                    }
                    TellForgeAISettings.getInstance().putModelInfoCache(saved)
                    pricingLookupModel = ""
                    setPricingSpinning(false)
                    if (stale) {
                        updateStickyHeader()
                        return@invokeLater
                    }
                    syncPricingIntoChat(model, saved.inputPricePerMillion, saved.cachedInputPricePerMillion, saved.outputPricePerMillion, saved.source, saved.checkedAt)
                    currentChatId?.let { TellForgeAIChatsState.getInstance(project).get(it) }?.let { chat ->
                        if (info.contextWindow != null) chat.contextWindow = info.contextWindow
                        chat.contextModel = model
                        TellForgeAIChatsState.getInstance(project).upsert(chat)
                    }
                    updateModelInfoLabel(profile, model, saved)
                    updateStickyHeader()
                }
            }
        }
    }

    private fun syncPricingIntoChat(model: String, input: Double?, cached: Double?, output: Double?, source: String, checkedAt: Long) {
        val id = currentChatId ?: return
        val chat = TellForgeAIChatsState.getInstance(project).get(id) ?: return
        if (chat.contextModel.isNotBlank() && chat.contextModel != model) chat.mixedModelsUsage = true
        chat.contextModel = model
        chat.inputPricePerMillion = input
        chat.cachedInputPricePerMillion = cached
        chat.outputPricePerMillion = output
        chat.pricingSource = source
        chat.pricingCheckedAt = checkedAt
        TellForgeAIChatsState.getInstance(project).upsert(chat)
    }

    private fun updateModelInfoLabel(profile: ProviderProfile, model: String, entry: dev.tellforgeai.settings.ModelInfoCacheEntry) {
        if (modelInfoCacheKey != entry.key) return
        modelInfoLabel.text = formatModelInfo(profile, model, entry.inputPricePerMillion, entry.cachedInputPricePerMillion, entry.outputPricePerMillion, entry.status, entry.checkedAt)
        chatModelInfoLabel.text = if (currentChatId == null) "" else modelInfoLabel.text
        val tooltip = buildString {
            append("Provider: ${profile.trimmedBaseUrl()}\nModel: $model")
            if (entry.source.isNotBlank()) append("\nSource: ${entry.source}")
            if (entry.lookupInputTokens + entry.lookupOutputTokens > 0) append("\nThe extra pricing lookup used ${entry.lookupInputTokens} input and ${entry.lookupOutputTokens} output tokens.")
        }
        modelInfoLabel.toolTipText = tooltip
        chatModelInfoLabel.toolTipText = tooltip
    }

    /** True while a pricing lookup for this key is in flight. */
    private fun statusIsFetching(entry: dev.tellforgeai.settings.ModelInfoCacheEntry?) =
        entry != null && pricingLookupModel == entry.key

    /** Rotating refresh icon while a pricing lookup runs; static (with click-to-refresh hint) otherwise. */
    private fun setPricingSpinning(spinning: Boolean) {
        refreshPricingButton.icon = if (spinning) refreshIconSpin else refreshIconIdle
        refreshPricingButton.isEnabled = !spinning
        refreshPricingButton.toolTipText =
            if (spinning) "Fetching model pricing…" else "Refresh model pricing"
    }

    /** Manual re-fetch of pricing for the currently selected provider/model, bypassing the cache. */
    private fun onRefreshPricing() {
        val profile = profileCombo.selectedItem as? ProviderProfile ?: return
        val model = profile.modelFor(mode == Mode.PLAN).trim()
        if (model.isBlank()) return
        val settings = TellForgeAISettings.getInstance()
        val key = modelInfoCacheKey
        if (key.isBlank() || pricingLookupModel == key) return // already running
        pricingLookupModel = key
        setPricingSpinning(true)
        modelInfoLabel.text = "${profile.name} · $model · Fetching model pricing…"
        chatModelInfoLabel.text = if (currentChatId == null) "" else modelInfoLabel.text
        val previous = settings.modelInfoCache(key) ?: settings.expiredModelInfoCache(key)
        val entry = previous ?: dev.tellforgeai.settings.ModelInfoCacheEntry().apply { this.key = key }
        entry.status = "Fetching"
        settings.putModelInfoCache(entry)
        ModelInfoLookup.lookup(profile, model, settings.aiPricingLookupEnabled()) { info ->
            ApplicationManager.getApplication().invokeLater {
                val stale = modelInfoCacheKey != key
                val saved = dev.tellforgeai.settings.ModelInfoCacheEntry().apply {
                    this.key = key
                    contextWindow = info.contextWindow ?: entry.contextWindow
                    inputPricePerMillion = info.pricing?.inputPerMillion
                    cachedInputPricePerMillion = info.pricing?.cachedInputPerMillion
                    outputPricePerMillion = info.pricing?.outputPerMillion
                    source = info.pricing?.let { "${it.sourceTitle} — ${it.sourceUrl}" }.orEmpty()
                    checkedAt = System.currentTimeMillis()
                    status = info.status
                    lookupInputTokens = info.lookupInputTokens.toLong()
                    lookupOutputTokens = info.lookupOutputTokens.toLong()
                }
                TellForgeAISettings.getInstance().putModelInfoCache(saved)
                pricingLookupModel = ""
                setPricingSpinning(false)
                if (stale) {
                    updateStickyHeader()
                    return@invokeLater
                }
                syncPricingIntoChat(model, saved.inputPricePerMillion, saved.cachedInputPricePerMillion, saved.outputPricePerMillion, saved.source, saved.checkedAt)
                currentChatId?.let { TellForgeAIChatsState.getInstance(project).get(it) }?.let { chat ->
                    if (info.contextWindow != null) chat.contextWindow = info.contextWindow
                    chat.contextModel = model
                    TellForgeAIChatsState.getInstance(project).upsert(chat)
                }
                updateModelInfoLabel(profile, model, saved)
                updateStickyHeader()
            }
        }
    }

    private fun formatModelInfo(profile: ProviderProfile, model: String, input: Double?, cached: Double?, output: Double?, status: String, checkedAt: Long): String {
        fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.3f".format(v).trimEnd('0').trimEnd('.')
        val rates = if (input != null && output != null) {
            "Input $${fmt(input)}/M${cached?.let { " · cached $${fmt(it)}/M" }.orEmpty()} · Output $${fmt(output)}/M"
        } else status
        val age = if (checkedAt == 0L) "" else " · ${relativeTime(checkedAt)}"
        val ai = if (TellForgeAISettings.getInstance().aiPricingLookupEnabled()) " · AI lookup enabled (extra tokens)" else ""
        return "${profile.name} · $model · $rates$age$ai"
    }

    private fun setMode(m: Mode) {
        mode = m
        (profileCombo.selectedItem as? ProviderProfile)?.let { ensureModelInfo(it) }
        // Two separate ButtonGroups (chat card + home card); keep both in sync.
        planButton.isSelected = m == Mode.PLAN
        actButton.isSelected = m == Mode.ACT
        homePlanButton.isSelected = m == Mode.PLAN
        homeActButton.isSelected = m == Mode.ACT
    }

    private fun send() {
        val profile = TellForgeAIProjectState.getInstance(project).selectedProfile()
        if (profile == null) {
            Messages.showInfoMessage(project, "Add a provider first (gear icon).", "TellForgeAI")
            return
        }
        val images = pendingImages.toList()
        val text = inputArea.text.trim()
        if ((text.isEmpty() && images.isEmpty()) || streaming) return
        pendingImages.clear()
        pendingImageHashes.clear()
        refreshImageStrip()
        inputArea.text = ""

        // First message in a chat: create the session so it's persisted from the start.
        if (currentChatId == null) {
            val chat = ChatSession.new(text)
            currentChatId = chat.id
            TellForgeAIChatsState.getInstance(project).upsert(chat)
        }

        history += ChatMessage.user(text).apply { this.images = images }
        toolRounds = 0
        persistChat()
        appendUser(text, images)
        setStreaming(true)
        updateStickyHeader()

        startTurn()
    }

    /**
     * One request/response round-trip. If the model responds with tool calls,
     * they are executed (with approval for commands, unless the user disabled
     * the approval prompt) and the results are fed back as a follow-up turn,
     * until the model produces a final answer.
     */
    private fun startTurn() {
        val profile = TellForgeAIProjectState.getInstance(project).selectedProfile() ?: return
        ensureModelInfo(profile)

        // system prompt (with project context) is supplied fresh each request
        val context = buildProjectContext()
        val system = ChatMessage.system(Prompts.system(mode == Mode.PLAN, context))
        val originalMessages = listOf(system) + history
        val budget = tokenBudget(profile)
        val messages = if (budget != null) {
            val fitted = HistoryTrimmer.fit(originalMessages, budget)
            if (ContextGuard.wasTrimmed(originalMessages, fitted)) contextTrimNotice(fitted.size, originalMessages.size)
            fitted
        } else {
            originalMessages
        }
        lastAssistantMarkdown = StringBuilder()
        lastReasoning = StringBuilder()
        client.stream(profile, mode == Mode.PLAN, messages, ToolRegistry.specs(mode == Mode.PLAN)) { event ->
            // invoked on a background thread; marshal UI updates to the EDT
            ApplicationManager.getApplication().invokeLater {
                if (!streaming) return@invokeLater // user pressed Stop; ignore late events
                when (event) {
                    is LlmClient.Event.Delta -> appendAssistantDelta(event.text)
                    is LlmClient.Event.Reasoning -> appendReasoningDelta(event.text)
                    is LlmClient.Event.Done -> onTurnDone(event)
                    is LlmClient.Event.Error -> {
                        finishAssistantBlock()
                        appendSystem("Error: ${event.message}")
                        setStreaming(false)
                    }
                }
            }
        }
    }

    private fun onTurnDone(event: LlmClient.Event.Done) {
        event.usage?.let { usage ->
            lastPromptTokens = usage.promptTokens
            val chat = currentChatId?.let { TellForgeAIChatsState.getInstance(project).get(it) }
            if (chat != null) {
                val usedModel = TellForgeAIProjectState.getInstance(project).selectedProfile()?.modelFor(mode == Mode.PLAN).orEmpty()
                if (chat.contextModel.isBlank()) chat.contextModel = usedModel
                else if (usedModel.isNotBlank() && chat.contextModel != usedModel) chat.mixedModelsUsage = true
                chat.incomingTokens += usage.promptTokens.coerceAtLeast(0).toLong()
                chat.cachedInputTokens += (usage.cachedPromptTokens ?: 0).coerceIn(0, usage.promptTokens.coerceAtLeast(0)).toLong()
                chat.outgoingTokens += usage.completionTokens.coerceAtLeast(0).toLong()
                TellForgeAIChatsState.getInstance(project).upsert(chat)
            }
            updateStickyHeader()
        }
        // Near-full warning, once per chat: even with trimming, exploding
        // history (huge tool outputs, giant pastes) can approach the window.
        if (event.usage != null && !contextTrimNotified) {
            val profile = TellForgeAIProjectState.getInstance(project).selectedProfile()
            val window = contextWindow(profile)
            if (window != null && ContextGuard.nearFull(event.usage.promptTokens, window)) {
                contextTrimNotified = true
                appendSystem(
                    "⚠ Context is nearly full (${event.usage.promptTokens} / $window tokens). " +
                        "Long-term accuracy degrades past this point — consider starting a new chat."
                )
            }
        }
        if (event.toolCalls.isEmpty()) {
            // Plain final answer.
            finishAssistantBlock()
            if (event.fullText.isNotBlank()) history += ChatMessage.assistant(event.fullText)
            persistChat()
            setStreaming(false)
            markTurnComplete()
            return
        }
        // Loop guard: a model that keeps requesting tools would otherwise loop
        // (and re-send the whole history each round) indefinitely.
        if (toolRounds >= MAX_TOOL_ROUNDS) {
            finishAssistantBlock()
            appendSystem("Stopped after $MAX_TOOL_ROUNDS tool rounds to avoid an endless loop. " +
                "Press Send to let it continue.")
            if (event.fullText.isNotBlank()) history += ChatMessage.assistant(event.fullText)
            history += ChatMessage.user(
                "Tool loop stopped after $MAX_TOOL_ROUNDS rounds. Summarize what you have and give a final answer now."
            )
            persistChat()
            setStreaming(false)
            markTurnComplete()
            return
        }
        toolRounds++
        // Defensive guard: some providers (e.g. GLM on deepinfra) occasionally
        // finish a tool call with a blank function name (arguments intact).
        // Executing it would return "unknown tool ''" and the model would retry
        // the same broken call until the round limit — surface it and stop here.
        val nameless = event.toolCalls.filter { it.name.isBlank() }
        if (nameless.isNotEmpty()) {
            LOG.warn(
                "TellForgeAI: provider returned ${nameless.size} tool call(s) with a blank name; " +
                    "arguments: ${nameless.joinToString("; ") { it.arguments.take(200) }} " +
                    "(raw SSE deltas were logged by LlmClient)"
            )
            finishAssistantBlock()
            appendSystem(
                "Error: provider sent ${nameless.size} tool call(s) without a function name " +
                    "(arguments: ${nameless.joinToString("; ") { it.arguments.take(80) }}). " +
                    "The turn was stopped instead of retrying — see idea.log for the raw stream."
            )
            setStreaming(false)
            markTurnComplete()
            return
        }
        // Record the assistant's tool-call message, render each call, execute
        // off-EDT, then feed the results back as the next turn.
        if (event.fullText.isNotBlank()) {
            finishedBlocks.add(bubble(
                "${mode.label} · ${esc(profileLabel())}",
                markdownToHtml(event.fullText), assistantBubbleBg,
            ))
        }
        lastAssistantMarkdown = StringBuilder()
        lastReasoning = StringBuilder()
        history += ChatMessage.assistantWithToolCalls(event.fullText, event.toolCalls)
        persistChat()

        val calls = event.toolCalls
        calls.forEach { appendToolPending(it) }
        Thread {
            try {
                val results = calls.map { call ->
                    val result = ToolRegistry.execute(
                        project, call.name, call.arguments, mode == Mode.PLAN,
                    ) { command ->
                        // If the user disabled the approval prompt, run without asking.
                        if (!askPermissionCheckBox.isSelected) return@execute true
                        // Approval dialog must run on the EDT; block the worker until answered.
                        val approved = java.util.concurrent.atomic.AtomicBoolean(false)
                        val latch = java.util.concurrent.CountDownLatch(1)
                        ApplicationManager.getApplication().invokeLater {
                            approved.set(
                                Messages.showYesNoDialog(
                                    project,
                                    "TellForgeAI wants to run this command:\n\n$command\n\nAllow?",
                                    "TellForgeAI — Run command",
                                    Messages.getQuestionIcon(),
                                ) == Messages.YES
                            )
                            latch.countDown()
                        }
                        // Timeout so a lost dialog can't wedge the tool thread forever.
                        latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
                        approved.get()
                    }
                    call to result
                }
                ApplicationManager.getApplication().invokeLater {
                    for ((call, result) in results) {
                        appendToolResult(call, result)
                        // Truncate what goes back to the model: a huge tool result
                        // would otherwise be re-sent on every subsequent turn.
                        history += ChatMessage.toolResult(call.id, result.take(MAX_TOOL_RESULT_CHARS))
                    }
                    persistChat()
                    if (streaming) {
                        // The previous stream's job may still be finishing when Done
                        // was delivered; defer until the client is actually idle so
                        // stream()'s isRunning() guard doesn't drop this turn.
                        client.whenIdle {
                            ApplicationManager.getApplication().invokeLater {
                                if (streaming) startTurn()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Never leave the panel stuck in "Thinking" if tool execution blows up.
                ApplicationManager.getApplication().invokeLater {
                    appendSystem("Tool execution failed: ${e.message ?: e.javaClass.simpleName}")
                    setStreaming(false)
                }
            }
        }.start()
    }

    /**
     * Per-request prompt-token budget: detected/known context window minus a
     * reserve for the answer. null when no window is known — in that case
     * history is sent untrimmed.
     */
    private fun contextWindow(profile: ProviderProfile?): Int? {
        val chat = currentChatId?.let { TellForgeAIChatsState.getInstance(project).get(it) }
        val activeModel = profile?.modelFor(mode == Mode.PLAN).orEmpty().trim()
        return ContextGuard.contextWindow(
            chatWindow = chat?.contextWindow,
            chatModel = chat?.contextModel,
            activeModel = activeModel,
            profileWindow = profile?.contextWindow,
        )
    }

    /** Per-request prompt-token budget, null when no window is known. */
    private fun tokenBudget(profile: ProviderProfile): Int? =
        contextWindow(profile)?.let { it - ContextGuard.RESERVE_TOKENS }

    /** Once-per-chat transcript note that older tool results/turns were trimmed. */
    private fun contextTrimNotice(keptMessages: Int, totalMessages: Int) {
        if (contextTrimNotified) return
        contextTrimNotified = true
        appendSystem(ContextGuard.trimNotice(totalMessages, keptMessages))
    }

    /**
     * Compact project snapshot for the system prompt; built off-EDT, cached 60s.
     */
    private fun buildProjectContext(): String {
        if (cachedContext.isNotEmpty() && System.currentTimeMillis() - contextFetchedAt < 60_000) {
            return cachedContext
        }
        // Don't build on the EDT (VFS walk + git call); stale context beats a frozen UI.
        if (ApplicationManager.getApplication().isDispatchThread) return cachedContext
        return try {
            ProjectContext.build(project).also {
                cachedContext = it
                contextFetchedAt = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            cachedContext
        }
    }

    // --- tool transcript rendering ---

    private fun argsSummary(call: ToolCall): String = try {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(
            call.arguments.ifBlank { "{}" }
        ).jsonObject
        o.entries.joinToString(", ") { "${it.key}=${it.value}" }.take(160)
    } catch (_: Exception) {
        call.arguments.take(160)
    }

    private fun appendToolPending(call: ToolCall) {
        finishedBlocks.add(toolBubble("🔧 ${esc(call.name)}(${esc(argsSummary(call))})", "<i>running…</i>"))
        repaintTranscript()
    }

    private fun appendToolResult(call: ToolCall, result: String) {
        // Compact one-line summaries in the transcript — the full tool output is
        // already in the model context, no need to dump (possibly truncated)
        // file contents into the UI.
        val path = argString(call, "path")
        val lineCount = result.count { it == '\n' } + if (result.isNotEmpty()) 1 else 0
        val bodyHtml = when (call.name) {
            "read_file" -> summaryLine(
                "📄", path, "$lineCount lines · full content in model context",
            )
            "search_files" -> summaryLine(
                "🔍", argString(call, "query"), "$lineCount matches · details in model context",
            )
            "list_files" -> summaryLine(
                "📂", path.ifBlank { "project root" }, "$lineCount entries",
            )
            "write_file" -> summaryLine("📝", path, "written in editor (undoable)")
            "edit_file" -> summaryLine("✏️", path, "edited in editor (undoable)")
            else -> {
                // run_command etc.: keep the output visible, capped.
                val body = if (result.length > 2000) esc(result.take(2000)) + "\n... (truncated)" else esc(result)
                "<pre style='margin:4px 0;white-space:pre-wrap'>$body</pre>"
            }
        }
        finishedBlocks.add(toolBubble(
            "🔧 ${esc(call.name)}(${esc(argsSummary(call))})",
            bodyHtml,
        ))
        repaintTranscript()
    }

    private fun summaryLine(icon: String, main: String, note: String): String =
        "<div style='margin:4px 0'>$icon ${esc(main)} · ${esc(note)}</div>"

    /** Extracts a string argument from the tool call's JSON arguments. */
    private fun argString(call: ToolCall, key: String): String = try {
        kotlinx.serialization.json.Json.parseToJsonElement(
            call.arguments.ifBlank { "{}" }
        ).jsonObject[key]?.toString()?.trim('"') ?: ""
    } catch (_: Exception) {
        ""
    }

    /** A padded tool bubble; table cells preserve padding and background in Swing's HTML renderer. */
    private fun toolBubble(title: String, bodyHtml: String): String =
        "<table width='100%' cellpadding=0 cellspacing=0><tr>" +
            "<td bgcolor='${hex(if (UIUtil.isUnderDarcula()) Color(0x2E3338) else Color(0xF0F5EC))}' " +
            "style='padding:6px 10px'>" +
            "<div class='who' style='font-size:0.9em'>$title</div><div>$bodyHtml</div>" +
            "</td></tr></table>" +
            "<table width='100%' cellpadding=0 cellspacing=0><tr><td style='font-size:6px'>&nbsp;</td></tr></table>"

    private fun onTestConnection() {
        val profile = profileCombo.selectedItem as? ProviderProfile ?: return
        testButton.isEnabled = false
        testButton.text = "Testing…"
        client.testConnection(profile) { result ->
            ApplicationManager.getApplication().invokeLater {
                testButton.isEnabled = true
                testButton.text = "Test"
                if (result.startsWith("Connection OK")) {
                    appendSystem("✔ ${profile.name}: $result")
                } else {
                    appendSystem("✘ ${profile.name}: $result")
                }
            }
        }
    }

    /** Stop button: abort the request/loop immediately and clear the "Thinking…" state. */
    private fun stop() {
        if (!streaming) return
        client.cancel()
        finishAssistantBlock()
        setStreaming(false)
        appendSystem("Stopped.")
    }

    private fun setStreaming(s: Boolean) {
        streaming = s
        sendStopButton.text = if (s) "Stop" else "Send"
        inputArea.isEnabled = !s
        if (s) {
            thinkingDots = 0
            completedMode = null
            thinkingTimer.start()
        } else {
            thinkingTimer.stop()
        }
    }

    /** Marks the just-finished turn as complete (green status line, mode-aware label). */
    private fun markTurnComplete() {
        completedMode = mode
        repaintTranscript()
    }

    // --- transcript rendering ---

    private fun appendUser(text: String, images: List<String> = emptyList()) {
        val imgsHtml = if (images.isEmpty()) "" else
            "<div style='margin:4px 0'>" + images.joinToString("") {
                "<img src='$it' width='128' height='128' style='margin:2px'/>"
            } + "</div>"
        finishedBlocks.add(bubble(
            "You",
            imgsHtml + esc(text).replace("\n", "<br/>"),
            userBubbleBg,
        ))
        streamingHeader = ""
        repaintTranscript()
    }

    private fun appendAssistantDelta(delta: String) {
        lastAssistantMarkdown.append(delta)
        if (lastAssistantMarkdown.isNotEmpty()) thinkingTimer.stop()
        // Re-render ONLY the in-progress assistant message; earlier blocks stay fixed.
        // Throttled: full HTML re-render per token is far too expensive.
        throttledRepaint()
    }

    /**
     * Streaming reasoning (model "thinking"). NOT rendered as text — that was a
     * wall of scrolling prose and a repaint storm. Instead we show a compact
     * "N KB reasoned" status line, repainted at most ~5x/second (the dots on the
     * 400 ms timer keep it feeling alive between repaints).
     */
    private fun appendReasoningDelta(text: String) {
        lastReasoning.append(text)
        throttledRepaint()
    }

    /** Repaint at most every [REPAINT_INTERVAL_MS]; collapses per-token repaint storms. */
    private fun throttledRepaint() {
        val now = System.currentTimeMillis()
        if (now - lastRepaintAt >= REPAINT_INTERVAL_MS) {
            lastRepaintAt = now
            repaintTranscript()
        }
    }

    /** Finish the current streaming block and move it into the fixed transcript. */
    private fun finishAssistantBlock() {
        // Empty assistant response (e.g. error/stop before any token): don't render a blank bubble.
        if (lastAssistantMarkdown.isBlank()) {
            streamingHeader = ""
            lastAssistantMarkdown = StringBuilder()
            lastReasoning = StringBuilder()
            repaintTranscript()
            return
        }
        val rendered = markdownToHtml(lastAssistantMarkdown.toString())
        finishedBlocks.add(bubble("${mode.label} · ${esc(profileLabel())}", rendered, assistantBubbleBg))
        streamingHeader = ""
        lastAssistantMarkdown = StringBuilder()
        lastReasoning = StringBuilder()
        // Always repaint immediately: throttle in appendXxxDelta must not leave a
        // stale "Thinking" bubble after the turn ends.
        repaintTranscript()
    }

    private fun appendSystem(text: String) {
        finishedBlocks.add("<div class='mode'>${esc(text)}</div><hr/>")
        repaintTranscript()
    }

    private fun profileLabel(): String =
        (profileCombo.selectedItem as? ProviderProfile)?.name ?: ""

    /**
     * Incremental renderer. The transcript is split into a *finished* part
     * (blocks appended once, inserted directly into the document) and a
     * *transient tail* (streaming bubble / "Thinking…" status / "complete"
     * status), which lives in a separate lightweight pane so it can be
     * re-rendered per token tick without touching the finished transcript.
     *
     * The previous approach re-parsed + re-laid-out the WHOLE transcript HTML on
     * every tool result and throttle tick: O(n²) over a long tool loop, pegging
     * the EDT and freezing the IDE. See HTMLDocument#insertBeforeEnd.
     */
    private fun repaintTranscript() {
        val wasNearBottom = isNearBottom()
        // 1) Append new finished blocks (each inserted exactly once).
        while (renderedBlocks < finishedBlocks.size) {
            insertAtBodyEnd(finishedBlocks[renderedBlocks])
            renderedBlocks++
        }
        // 2) Re-render only the transient tail in the tail pane.
        tailPane.text = "<html><body style='margin:0 12px 12px 12px'>${tailHtml()}</body></html>"
        tailPaneVisible()
        if (wasNearBottom) scrollToBottom()
        updateStickyHeader()
    }

    /** Number of finished blocks already inserted into the live document. */
    private var renderedBlocks = 0
    /** Invalidates deferred scrolls when a chat is rebuilt or a newer scroll wins. */
    private var scrollRequest = 0

    private fun tailHtml(): String = when {
        lastAssistantMarkdown.isNotEmpty() -> bubble(
            "${mode.label} · ${esc(profileLabel())}",
            markdownToHtml(lastAssistantMarkdown.toString()),
            assistantBubbleBg,
        )
        // Model is streaming its reasoning/thinking: compact one-line status
        // ("Thinking · glm — 12.4 KB reasoned") instead of dumping the text.
        lastReasoning.isNotEmpty() -> bubble(
            "Thinking · ${esc(profileLabel())}",
            "<div style='color:#8a8a8a;font-style:italic'>%.1f KB reasoned<span>%s</span></div>"
                .format(lastReasoning.length / 1024.0, ".".repeat(thinkingDots)),
            assistantBubbleBg,
        )
        // Waiting for the first streamed token: show an animated "Thinking…" status.
        streaming -> "<div class='mode' style='font-style:italic'>Thinking${".".repeat(thinkingDots)}</div>"
        completedMode != null -> "<div class='mode' style='color:#3fb950;font-weight:bold'>" +
            (if (completedMode == Mode.PLAN) "✓ Planning complete" else "✓ Task complete") + "</div>"
        else -> ""
    }

    /** Hides the tail pane entirely when there's nothing transient to show. */
    private fun tailPaneVisible() {
        // Keep the component in the scroll layout but zero-height when empty,
        // so an empty tail never leaves a blank strip under the last bubble.
        val empty = tailHtml().isEmpty()
        tailPane.preferredSize = if (empty) Dimension(0, 0) else null
        tailPane.isVisible = !empty
    }

    /** Inserts one block into the actual HTML body, not the document's synthetic root. */
    private fun insertAtBodyEnd(html: String) {
        try {
            val doc = transcriptPane.document as HTMLDocument
            fun findBody(element: Element): Element? {
                if (element.attributes.getAttribute(StyleConstants.NameAttribute) == HTML.Tag.BODY) return element
                for (index in 0 until element.elementCount) {
                    findBody(element.getElement(index))?.let { return it }
                }
                return null
            }
            val body = findBody(doc.defaultRootElement)
                ?: error("Transcript HTML has no body element")
            doc.insertBeforeEnd(body, html)
        } catch (e: Exception) {
            // Keep the transcript usable if Swing's HTML parser rejects an inserted fragment.
            LOG.warn("TellForgeAI: incremental transcript insertion failed; rebuilding transcript", e)
            transcriptPane.text =
                "<html><body style='margin:12px'>${finishedBlocks.take(renderedBlocks + 1).joinToString("")}</body></html>"
        }
    }

    /** Full rebuild — used once at open/new-chat/restore-history time, never per tick. */
    private fun rebuildTranscript() {
        transcriptPane.text = "<html><body style='margin:12px'>${finishedBlocks.joinToString("")}</body></html>"
        renderedBlocks = finishedBlocks.size
        tailPane.text = "<html><body style='margin:0 12px 12px 12px'>${tailHtml()}</body></html>"
        tailPaneVisible()
        // Rebuilds happen when opening/restoring a chat or starting a fresh one:
        // always show the beginning, after Swing has recalculated the document layout.
        val request = ++scrollRequest
        SwingUtilities.invokeLater {
            if (request == scrollRequest) {
                chatScroll.viewport.viewPosition = java.awt.Point(0, 0)
            }
        }
    }

    /** True if the transcript scrollbar is at (or within 40px of) the bottom. */
    private fun isNearBottom(): Boolean {
        val sb = chatScroll.verticalScrollBar ?: return true
        return sb.value + sb.visibleAmount >= sb.maximum - 40
    }

    /**
     * Vertical content flow: finished transcript followed immediately by the
     * transient tail. The old BorderLayout stretched the transcript to fill the
     * viewport and pinned the tail at the bottom, making messages look detached.
     */
    private fun tailStackPanel(): JPanel = object : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            transcriptPane.alignmentX = Component.LEFT_ALIGNMENT
            tailPane.alignmentX = Component.LEFT_ALIGNMENT
            add(transcriptPane)
            add(tailPane)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int = 16
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int): Int =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    /** Pins the shared transcript/tail viewport to the bottom after layout settles. */
    private fun scrollToBottom() {
        val request = ++scrollRequest
        SwingUtilities.invokeLater {
            if (request == scrollRequest) {
                chatScroll.verticalScrollBar?.let { it.value = it.maximum }
            }
        }
    }

    /**
     * Refreshes the sticky header: the first user message of the chat plus a
     * context usage meter (rough estimate of tokens sent vs. the provider's
     * configured context window). Cheap (char counting), safe to call often.
     */
    private fun updateStickyHeader() {
        firstMessageLabel.text = history.firstOrNull { it.role == "user" }
            ?.content?.lineSequence()?.firstOrNull().orEmpty()
        val id = currentChatId
        val chat = id?.let { TellForgeAIChatsState.getInstance(project).get(it) }
        if (id == null) (profileCombo.selectedItem as? ProviderProfile)?.let { ensureModelInfo(it) }
        val used = maxOf(estimateUsedTokens(), lastPromptTokens)
        val configuredWindow = TellForgeAIProjectState.getInstance(project).selectedProfile()?.contextWindow ?: 128_000
        val window = chat?.contextWindow?.takeIf { it > 0 } ?: configuredWindow
        contextBar.value = ((used.toDouble() / window) * 1000).toInt().coerceIn(0, 1000)
        contextLabel.text = "${formatTokens(used)} / ${formatTokens(window)}"
        val inTokens = chat?.incomingTokens ?: 0L
        val cacheTokens = chat?.cachedInputTokens ?: 0L
        val outTokens = chat?.outgoingTokens ?: 0L
        val cost = if (chat?.inputPricePerMillion != null && chat.outputPricePerMillion != null) {
            val uncachedTokens = (inTokens - cacheTokens).coerceAtLeast(0)
            val cachedRate = chat.cachedInputPricePerMillion ?: chat.inputPricePerMillion!!
            (uncachedTokens * chat.inputPricePerMillion!! + cacheTokens * cachedRate + outTokens * chat.outputPricePerMillion!!) / 1_000_000.0
        } else null
        usageLabel.text = if (id == null) "" else buildString {
            append("In ").append(formatTokensLong(inTokens))
            append(" · Cached ").append(formatTokensLong(cacheTokens))
            append(" · Out ").append(formatTokensLong(outTokens))
            append(" · Total ").append(cost?.let { formatUsd(it) } ?: "—")
        }
        usageLabel.toolTipText = if (chat == null) null else buildString {
            append("Cumulative provider-reported usage for this chat. Cached input is included in input tokens.")
            val cachedInfo = TellForgeAISettings.getInstance().modelInfoCache(modelInfoCacheKey)
            if (cachedInfo != null && cachedInfo.lookupInputTokens + cachedInfo.lookupOutputTokens > 0) {
                append("\nSeparate pricing lookup call used ${cachedInfo.lookupInputTokens} input and ${cachedInfo.lookupOutputTokens} output tokens; these are not included in chat totals.")
            }
            if (chat.mixedModelsUsage) append("\nThis chat used multiple models; displayed rates apply only to the currently resolved model, so total cost may be incomplete.")
            if (chat.contextWindow > 0) append("\nContext window: ${formatTokens(chat.contextWindow)} tokens (${chat.contextModel}).")
            if (cost != null) {
                append("\nRates: $${chat.inputPricePerMillion}/million input")
                chat.cachedInputPricePerMillion?.let { append(", $${it}/million cached input") }
                append(", $${chat.outputPricePerMillion}/million output.")
                append("\nSource: ${chat.pricingSource}")
            } else if (chat.pricingCheckedAt > 0) {
                append("\nPricing not confidently resolved; cost is not estimated.")
            } else {
                append("\nModel pricing lookup pending or unavailable.")
            }
        }
    }

    /**
     * Rough token estimate: ~4 characters per token across the system context,
     * all messages and tool-call arguments. Good enough for a usage meter.
     */
    private fun estimateUsedTokens(): Int {
        var chars = cachedContext.length
        for (m in history) {
            chars += m.content.length
            for (c in m.toolCalls) chars += c.arguments.length
        }
        return chars / 4
    }

    private fun formatTokens(t: Int): String =
        if (t >= 1000) String.format("%.1fk", t / 1000.0) else t.toString()

    private fun formatTokensLong(t: Long): String =
        if (t >= 1000) String.format("%.1fk", t / 1000.0) else t.toString()

    private fun formatUsd(value: Double): String =
        if (value < 0.01) String.format("$%.5f", value) else String.format("$%.3f", value)

    /** A padded message bubble with a small header, rendered as a full-width colored table. */
    private fun bubble(who: String, bodyHtml: String, bg: Color): String =
        "<table width='100%' cellpadding=0 cellspacing=0><tr>" +
            "<td bgcolor='${hex(bg)}' style='padding:8px 10px'>" +
            "<div class='who'>$who</div><div>$bodyHtml</div>" +
            "</td></tr></table>" +
            "<table width='100%' cellpadding=0 cellspacing=0><tr><td style='font-size:6px'>&nbsp;</td></tr></table>"

    private fun hex(c: Color): String =
        String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    /**
     * Installs an image-paste handler on a chat input area. Paste/drop of an
     * image (clipboard screenshot or dragged image file) is captured, downscaled
     * to [imageMaxSize] px, encoded as a base64 PNG data URL and queued as a
     * pending attachment. Text paste/drop falls through to the default handler.
     */
    private fun installImagePaste(area: JTextArea) {
        val textTransferHandler = area.transferHandler
        area.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                support.transferable?.transferDataFlavors?.any { isImageFlavor(it) } == true ||
                    textTransferHandler?.canImport(support) == true

            override fun importData(support: TransferSupport): Boolean {
                extractImage(support.transferable)?.let {
                    addPendingImage(it)
                    return true
                }
                return textTransferHandler?.importData(support) == true
            }

            /** True for any flavor carrying an image: raw image, image file list, or
             *  an "image/..." MIME flavor (how many clipboard sources expose screenshots). */
            private fun isImageFlavor(f: DataFlavor): Boolean =
                f == DataFlavor.imageFlavor ||
                    f == DataFlavor.javaFileListFlavor ||
                    f.mimeType.startsWith("image/")

            /**
             * Tries EVERY image flavor on the transferable — platform clipboard
             * providers often list several, and the first one may throw or decode
             * to null even though another would work.
             */
            private fun extractImage(t: Transferable): Image? {
                for (flavor in t.transferDataFlavors.filter { isImageFlavor(it) }) {
                    try {
                        val image: Image? = when {
                            flavor == DataFlavor.imageFlavor ->
                                t.getTransferData(flavor) as? Image
                            flavor == DataFlavor.javaFileListFlavor -> {
                                val file = (t.getTransferData(flavor) as List<*>)
                                    .filterIsInstance<java.io.File>()
                                    .firstOrNull {
                                        it.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp")
                                    }
                                file?.let { ImageIO.read(it) }
                            }
                            // Other image flavors (image/png, image/jpeg, ...) expose raw bytes.
                            else -> {
                                when (val data = t.getTransferData(flavor)) {
                                    is java.io.InputStream -> imageFromBytes(data.readBytes())
                                    is ByteArray -> imageFromBytes(data)
                                    is Image -> data
                                    else -> null
                                }
                            }
                        }
                        if (image != null) return image
                    } catch (e: Exception) {
                        LOG.warn("TellForgeAI: failed to extract image from flavor $flavor", e)
                    }
                }
                return null
            }
        }

        // Right-click context menu with an explicit "Paste image" action that reads
        // the system clipboard directly — covers cases where the IDE action system
        // or platform clipboard provider bypasses the Swing TransferHandler.
        area.componentPopupMenu = JPopupMenu().apply {
            add("Paste").addActionListener { pasteFromClipboard(area) }
            add("Paste image").addActionListener { pasteImageFromClipboard() }
        }

        // ⌘V / Ctrl+V: Swing InputMap bindings are consulted *after* the IDE
        // shortcut dispatcher, which binds $Paste and swallows the keystroke
        // before our component sees it. Register an AnAction with a
        // CustomShortcutSet on the text areas instead — IDE-registered
        // shortcuts win over both the IDE keymap and Swing keymaps.
        val originalPaste = area.actionMap.get(DefaultEditorKit.pasteAction)
        val pasteAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val contents = try {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                } catch (ex: Exception) {
                    LOG.warn("TellForgeAI: clipboard read failed", ex); null
                }
                val image = contents?.let { extractImageQuietly(it) }
                if (image != null) {
                    addPendingImage(image)
                    // If the clipboard also holds text (e.g. "Copy image" from a
                    // browser), still paste the text so nothing is lost.
                    val text = try {
                        contents.getTransferData(DataFlavor.stringFlavor) as? String
                    } catch (_: Exception) { null }
                    if (!text.isNullOrBlank()) area.replaceSelection(text)
                    return
                }
                originalPaste?.actionPerformed(ActionEvent(area, 0, DefaultEditorKit.pasteAction))
            }
        }
        pasteAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_V, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)),
            area,
        )
        Disposer.register(TellForgeAIToolWindowDisposable.get(project)) {
            area.unregisterKeyboardAction(KeyStroke.getKeyStroke(KeyEvent.VK_V, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx))
        }
    }

    /** Tolerant wrapper around [extractImageFromTransferable] — never throws. */
    private fun extractImageQuietly(t: java.awt.datatransfer.Transferable): Image? =
        try {
            extractImageFromTransferable(t)
        } catch (_: Exception) {
            null
        }

    /** Reads image bytes from any source, tolerating partial/transient stream failures. */
    private fun imageFromBytes(bytes: ByteArray): Image? =
        try {
            ImageIO.read(java.io.ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        }

    /**
     * Some clipboard/drag sources expose the image only as base64 text or a
     * "data:image/...;base64,..." URL inside an image MIME flavor — decode that too.
     */
    private fun imageFromDataUrl(text: String): Image? =
        try {
            val b64 = text.substringAfter("base64,").trim()
            val bytes = Base64.getMimeDecoder().decode(b64)
            imageFromBytes(bytes)
        } catch (_: Exception) {
            null
        }

    /**
     * Standalone image extraction from any Transferable (clipboard or drop),
     * trying every image flavor — mirrors the TransferHandler's nested logic,
     * which isn't reachable from the keybinding handler.
     */
    private fun extractImageFromTransferable(t: Any): Image? {
        val transferable = t as java.awt.datatransfer.Transferable
        fun isImageFlavor(f: DataFlavor): Boolean =
            f == DataFlavor.imageFlavor ||
                f == DataFlavor.javaFileListFlavor ||
                f.mimeType.startsWith("image/")
        for (flavor in transferable.transferDataFlavors.filter { isImageFlavor(it) }) {
            try {
                val image: Image? = when {
                    flavor == DataFlavor.imageFlavor -> transferable.getTransferData(flavor) as? Image
                    flavor == DataFlavor.javaFileListFlavor -> {
                        val file = (transferable.getTransferData(flavor) as List<*>)
                            .filterIsInstance<java.io.File>()
                            .firstOrNull {
                                it.extension.lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp")
                            }
                        file?.let { ImageIO.read(it) }
                    }
                    else -> when (val data = transferable.getTransferData(flavor)) {
                        is java.io.InputStream -> imageFromBytes(data.readBytes())
                        is ByteArray -> imageFromBytes(data)
                        is Image -> data
                        is java.io.Reader -> imageFromBytes(
                            data.readText().toByteArray(Charsets.ISO_8859_1)
                        )
                        // Some sources expose base64 text or a data URL as the image flavor.
                        is String -> imageFromDataUrl(data)
                        else -> null
                    }
                }
                if (image != null) return image
            } catch (_: Exception) { }
        }
        return null
    }

    /** Standard text paste (goes through the TransferHandler; images handled too). */
    private fun pasteFromClipboard(area: JTextArea) {
        val handler = area.transferHandler ?: return
        val contents = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        if (contents != null) {
            handler.importData(TransferHandler.TransferSupport(area, contents))
        }
    }

    /**
     * Reads the system clipboard directly and imports any image found, regardless
     * of how the clipboard provider exposes it.
     */
    private fun pasteImageFromClipboard() {
        try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null) ?: return
            val handler = inputArea.transferHandler
            if (handler != null) {
                val support = TransferHandler.TransferSupport(inputArea, contents)
                if (handler.canImport(support) && handler.importData(support)) return
            }
            // Last resort: scan all flavors manually.
            for (flavor in contents.transferDataFlavors) {
                if (!flavor.mimeType.startsWith("image/") && flavor != DataFlavor.imageFlavor) continue
                try {
                    when (val data = contents.getTransferData(flavor)) {
                        is Image -> return addPendingImage(data)
                        is java.io.InputStream -> imageFromBytes(data.readBytes())?.let { return addPendingImage(it) }
                        is ByteArray -> imageFromBytes(data)?.let { return addPendingImage(it) }
                        is String -> imageFromDataUrl(data)?.let { return addPendingImage(it) }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Downscales, encodes and queues a pasted image; shows it in the strip. */
    private fun addPendingImage(image: Image) {
        val dataUrl = try {
            val size = scaledSize(image, imageMaxSize)
            // Draw synchronously with bilinear interpolation (getScaledInstance is
            // async and can produce a blank result if drawn before it loads).
            val buf = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
            val g2 = buf.createGraphics().apply {
                addRenderingHints(
                    mapOf(
                        java.awt.RenderingHints.KEY_INTERPOLATION to
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                    ),
                )
            }
            g2.drawImage(image, 0, 0, size.width, size.height, null)
            g2.dispose()
            val out = ByteArrayOutputStream()
            ImageIO.write(buf, "png", out)
            val pngBytes = out.toByteArray()
            // Duplicate guard: identical pastes (same screenshot re-pasted) hash the
            // same because the downscale is deterministic; skip the repeat.
            val hash = MessageDigest.getInstance("SHA-256").digest(pngBytes)
                .joinToString("") { "%02x".format(it) }
            if (hash in pendingImageHashes) return
            pendingImageHashes += hash
            "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes)
        } catch (_: Exception) {
            return
        }
        pendingImages += dataUrl
        refreshImageStrip()
    }

    /** Scales [image] so the longest side is at most [max] px, preserving aspect. Returns the target size. */
    private fun scaledSize(image: Image, max: Int): Dimension {
        val loaded = ensureLoaded(image)
        val w = loaded.getWidth(null).coerceAtLeast(1)
        val h = loaded.getHeight(null).coerceAtLeast(1)
        if (w <= max && h <= max) return Dimension(w, h)
        val scale = max.toDouble() / maxOf(w, h)
        return Dimension((w * scale).toInt(), (h * scale).toInt())
    }

    /**
     * Clipboard/drag images can arrive unloaded (getWidth(null) == -1); drawing
     * such an image silently paints nothing, producing a blank attachment with
     * no error. Force a synchronous load via MediaTracker first.
     */
    private fun ensureLoaded(image: Image): Image {
        if (image.getWidth(null) >= 0 && image.getHeight(null) >= 0) return image
        val tracker = java.awt.MediaTracker(this)
        tracker.addImage(image, 0)
        try {
            tracker.waitForAll(2000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return image
    }

    /** Rebuilds the pending-image thumbnail strips above the input areas. */
    private fun refreshImageStrip() {
        for (strip in imageStrips) {
            strip.isVisible = pendingImages.isNotEmpty()
            val thumbs = mutableMapOf<Int, JLabel>()
            for ((idx, img) in pendingImages.withIndex()) {
                try {
                    // Thumbnail for display only — small, fixed height; the 256px
                    // data URL in pendingImages is the actual sent payload.
                    // NOTE: ImageIcon(String) treats the string as a file path —
                    // data URLs must be base64-decoded into raw PNG bytes first.
                    val base64 = img.substringAfter("base64,")
                    val bytes = Base64.getDecoder().decode(base64)
                    val full = ImageIcon(bytes) // decodes PNG bytes
                    val scale = thumbMaxSize.toFloat() / maxOf(
                        full.iconWidth.coerceAtLeast(1),
                        full.iconHeight.coerceAtLeast(1),
                    )
                    val w = (full.iconWidth * scale).toInt().coerceAtLeast(1)
                    val h = (full.iconHeight * scale).toInt().coerceAtLeast(1)
                    val scaledThumb = full.image.getScaledInstance(w, h, Image.SCALE_SMOOTH)
                    thumbs[idx] = JLabel(ImageIcon(scaledThumb)).apply {
                        preferredSize = Dimension(w, h)
                        toolTipText = "Pasted image ${idx + 1} of ${pendingImages.size} — click to remove"
                        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        border = JBUI.Borders.empty(0, 0, 0, 4)
                        addMouseListener(object : MouseAdapter() {
                            override fun mouseClicked(e: MouseEvent) {
                                pendingImages.removeAt(idx)
                                refreshImageStrip()
                            }
                        })
                    }
                } catch (_: Exception) { }
            }
            strip.setThumbs(thumbs.values.toList())
            strip.statusLabel.text =
                "${pendingImages.size} image${if (pendingImages.size == 1) "" else "s"} attached"
            strip.revalidate()
            strip.repaint()
        }
    }

    /**
     * Multiline-friendly input: Enter sends, Shift+Enter inserts a newline.
     * Implemented with the component's InputMap/ActionMap (not a KeyListener)
     * so it wins over the text component's default bindings.
     */
    private fun installEnterToSend(area: JTextArea, onSend: () -> Unit) {
        val sendAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onSend()
        }
        // Send on Enter...
        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SEND_ACTION_KEY)
        area.actionMap.put(SEND_ACTION_KEY, sendAction)
        // ...insert a newline on Shift+Enter (restores the binding Enter normally has).
        area.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
            DefaultEditorKit.insertBreakAction,
        )
        area.actionMap.put(
            DefaultEditorKit.insertBreakAction,
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = area.replaceSelection("\n")
            },
        )
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Minimal, streaming-friendly markdown: fenced code, bold, inline code. */
    private fun markdownToHtml(md: String): String {
        val sb = StringBuilder()
        var inCode = false
        for (line in md.split("\n")) {
            when {
                line.trimStart().startsWith("```") -> {
                    if (inCode) sb.append("</pre>") else sb.append("<pre>")
                    inCode = !inCode
                }
                inCode -> sb.append(esc(line)).append('\n')
                else -> {
                    var l = esc(line)
                    l = l.replace(Regex("`([^`]+)`")) { m -> "<code>${m.groupValues[1]}</code>" }
                    l = l.replace(Regex("\\*\\*([^*]+)\\*\\*")) { m -> "<b>${m.groupValues[1]}</b>" }
                    sb.append(l).append("<br/>")
                }
            }
        }
        if (inCode) sb.append("</pre>")
        return sb.toString()
    }

    companion object {
        /** Min interval between transcript re-renders during token streaming. */
        private const val REPAINT_INTERVAL_MS = 200L
        private const val CARD_HOME = "home"
        private const val CARD_CHAT = "chat"
        private const val SEND_ACTION_KEY = "tellforgeai.send"
        private const val ENTER_TO_SEND_TIP =
            "Enter to send — Shift+Enter for a new line"
        /** Max tool request/execute/feedback rounds per user turn, before forcing a wrap-up. */
        private const val MAX_TOOL_ROUNDS = 200
        /** Max characters of a tool result fed back into the model history. */
        private const val MAX_TOOL_RESULT_CHARS = 8_000
    }
}
