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
        val panel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
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
