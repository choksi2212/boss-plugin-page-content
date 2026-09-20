package ai.rever.boss.plugin.dynamic.pagecontent.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Structured content extracted from a single page in the integrated browser.
 *
 * [visibleText] is the rendered text with scripts/styles removed; [headings],
 * [links] and [meta] are also extracted so a consumer can pick what it needs
 * without re-running JS. [screenshotBase64] is optional because not every host
 * can capture a page snapshot, and even when it can, the result is expensive.
 *
 * Not every consumer wants every field - a UI panel shows a few, an agent
 * tool wants the whole thing as JSON - but the same type carries both.
 */
@Serializable
data class PageContent(
    val url: String,
    val title: String,
    val language: String?,
    val visibleText: String,
    val headings: List<Heading>,
    val links: List<PageLink>,
    val meta: Map<String, String>,
    val wordCount: Int,
    val readingTimeMinutes: Int,
    val screenshotBase64: String?,
)

@Serializable
data class Heading(
    val level: Int,
    val text: String,
)

@Serializable
data class PageLink(
    val href: String,
    val text: String,
    val isExternal: Boolean,
)

/**
 * Plugin API for getting structured content of the page the user is reading.
 *
 * Other plugins obtain an implementation through `context.getPluginAPI(
 * PageContentProvider::class.java)`; the page-content plugin publishes it on
 * load. Returns `null` from every method when no browser tab is available,
 * which is the normal state on a host without the browser plugin - callers
 * should treat null as "no answer yet" rather than as an error.
 *
 * The interface is defined here rather than in the host API jar so it can
 * ship without a host release. Plugins consume it through `getPluginAPI`,
 * which is host-typed, so the plugin API version governs its evolution the
 * same way it governs any plugin class.
 */
interface PageContentProvider {
    /**
     * The current active tab's content, or null if no browser tab is active
     * or the active tab is not a browser tab.
     */
    suspend fun current(): PageContent?

    /**
     * The content of a specific tab, or null if it is not a browser tab or
     * no longer exists.
     */
    suspend fun byTabId(tabId: String): PageContent?

    /**
     * Subscribe to changes (URL changes, navigations). Emits the new content
     * after each, or null when the active tab stops being a browser tab.
     */
    fun observe(): Flow<PageContent?>
}
