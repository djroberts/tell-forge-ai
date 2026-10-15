package dev.tellforgeai.settings

/**
 * The three kinds of page-mutating actions the wiki/Notion tools can perform,
 * shared by the Tela wiki and Notion integrations. Both per-project state
 * classes store a "skip approval" flag per action (the "Ask permission to …"
 * checkboxes in the Configure Integrations dialog, all three checked by
 * default):
 *
 *  - CREATE — "Ask permission to add page"
 *  - UPDATE — "Ask permission to update page"
 *  - DELETE — "Ask permission to delete page"
 */
enum class WikiWriteAction {
    CREATE, UPDATE, DELETE
}
