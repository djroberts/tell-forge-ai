package dev.tellforgeai.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level.PROJECT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class TellForgeAIToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val chat = ChatPanel(project)
        val instructions = InstructionsPanel(project)
        with(toolWindow.contentManager) {
            addContent(contentFactory.createContent(chat, "Chat", false))
            addContent(contentFactory.createContent(instructions, "Instructions", false))
        }
        // Pick up content changes written behind our back (e.g. the agent
        // pushing instructions_write results, or the Test button in the config
        // dialog) when the user flips to this tab.
        toolWindow.contentManager.addContentManagerListener(object :
            com.intellij.ui.content.ContentManagerListener {
            override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                (event.content.component as? InstructionsPanel)?.refresh()
            }
        })
    }
}

/**
 * Project-level disposable that owns chat client lifecycle.
 */
@Service(PROJECT)
class TellForgeAIToolWindowDisposable : Disposable {
    override fun dispose() = Unit

    companion object {
        fun get(project: Project): Disposable =
            project.getService(TellForgeAIToolWindowDisposable::class.java)
    }
}
