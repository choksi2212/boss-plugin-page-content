package ai.rever.boss.plugin.dynamic.pagecontent

import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.McpToolRegistry
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.pagecontent.api.PageContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Save a rendered note about the page to the project companion plugin if
 * it is loaded, falling back to the clipboard when it is not.
 *
 * The project companion plugin exposes its `project_companion_save_note`
 * MCP tool; we discover it through `McpToolRegistry.invoke`. Hard-coding
 * a plugin id and using `getPluginAPI` would be a compile-time dependency
 * on a sibling plugin, which this plugin deliberately does not have.
 */
class ProjectCompanionBridge(
    private val registry: McpToolRegistry?,
    private val clipboard: ClipboardProvider?,
) {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /**
     * Render a markdown note from [content] and persist it.
     *
     * Returns a status message naming what happened. The note text is
     * the same whether it goes to the project companion or the clipboard,
     * so re-pasting into the project companion later produces the same
     * document.
     */
    suspend fun saveAsNote(content: PageContent): String {
        val markdown = renderMarkdown(content)
        val companionOk = tryInvokeCompanion(markdown, content)
        if (companionOk == true) {
            return "Saved to project companion"
        }
        if (clipboard != null && clipboard.setText(markdown)) {
            return if (companionOk == false) {
                "Project companion refused the save - copied to clipboard"
            } else {
                "Project companion not loaded - copied to clipboard"
            }
        }
        return if (companionOk == false) {
            "Project companion refused the save - clipboard unavailable"
        } else {
            "Project companion not loaded - clipboard unavailable"
        }
    }

    private suspend fun tryInvokeCompanion(markdown: String, content: PageContent): Boolean? {
        val reg = registry ?: return null
        return runCatching {
            val args = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("title", content.title.ifBlank { content.url })
                    put("body", markdown)
                    put("source", "page-content-plugin")
                    put("url", content.url)
                },
            )
            val result = reg.invoke(COMPANION_TOOL_NAME, args)
            !result.isError
        }.getOrNull()
    }

    private fun renderMarkdown(content: PageContent): String {
        val sb = StringBuilder()
        sb.append("# ").append(content.title.ifBlank { "(untitled)" }).append('\n')
        sb.append(content.url).append('\n').append('\n')
        if (!content.language.isNullOrBlank()) {
            sb.append("Language: ").append(content.language).append('\n')
        }
        sb.append("Words: ").append(content.wordCount)
            .append(" - Reading time: ").append(content.readingTimeMinutes).append(" min")
            .append('\n').append('\n')
        if (content.headings.isNotEmpty()) {
            sb.append("## Headings").append('\n')
            for (h in content.headings) {
                sb.append("  ".repeat(h.level.coerceAtLeast(1)))
                    .append("- ").append(h.text).append('\n')
            }
            sb.append('\n')
        }
        if (content.meta.isNotEmpty()) {
            sb.append("## Meta").append('\n')
            for ((k, v) in content.meta) {
                sb.append("- ").append(k).append(": ").append(v).append('\n')
            }
            sb.append('\n')
        }
        sb.append("## Content").append('\n').append(content.visibleText).append('\n')
        return sb.toString()
    }

    companion object {
        /**
         * The MCP tool name we discover. The project companion plugin
         * documents this on its own; the only contract here is the string.
         */
        const val COMPANION_TOOL_NAME: String = "project_companion_save_note"
    }
}

/**
 * Resolve a [ProjectCompanionBridge] from the host context. Returns a
 * bridge that degrades to "no companion, use clipboard" when the registry
 * is missing, and to "no companion, no clipboard" when both are missing.
 */
fun PluginContext.projectCompanionBridge(): ProjectCompanionBridge =
    ProjectCompanionBridge(
        registry = mcpToolRegistry,
        clipboard = clipboardProvider,
    )
