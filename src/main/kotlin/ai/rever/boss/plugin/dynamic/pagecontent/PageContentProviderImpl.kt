package ai.rever.boss.plugin.dynamic.pagecontent

import ai.rever.boss.plugin.api.ActiveTabData
import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.dynamic.pagecontent.api.PageContent
import ai.rever.boss.plugin.dynamic.pagecontent.api.PageContentProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Implementation of [PageContentProvider] that runs the JS extractor against
 * the active browser tab through the host's [ActiveTabsProvider].
 *
 * The provider is constructed once per plugin load and lives for the
 * lifetime of the host's plugin registry; it caches nothing, so a long-lived
 * instance is cheap. The panel and the MCP tools share it.
 *
 * `activeTabsProvider` is nullable on hosts that predate the API; the
 * implementation degrades to "always null" rather than throwing, so the
 * panel and MCP tools can render a "browser not available" state without
 * a try/catch at every call site.
 */
class PageContentProviderImpl(
    private val activeTabsProvider: ActiveTabsProvider?,
) : PageContentProvider {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val changes = MutableSharedFlow<PageContent?>(
        replay = 0,
        extraBufferCapacity = 1,
    )

    override suspend fun current(): PageContent? = extract(null)

    override suspend fun byTabId(tabId: String): PageContent? = extract(tabId)

    override fun observe(): Flow<PageContent?> {
        // Distinct by content shape, not by tabId, so a reload that returns
        // the same extracted content does not fire a needless subscriber
        // recomposition. The "different page" signal is in the URL/title,
        // which are part of the content.
        return changes.filter { it != null }.distinctUntilChanged { a, b ->
            a?.url == b?.url && a?.title == b?.title
        }
    }

    /**
     * Re-extract and broadcast. Wired by the panel and the MCP tools' auto
     * refresh; not used by [current]/[byTabId] themselves.
     */
    suspend fun refreshCurrent(): PageContent? {
        val content = extract(null)
        changes.tryEmit(content)
        return content
    }

    /**
     * Find a browser tab and run the extractor against it.
     *
     * When [tabId] is null the focused pane's browser tab is preferred,
     * falling back to any browser tab in the window. A tab id that is not a
     * browser tab, or no longer exists, returns null.
     */
    private suspend fun extract(tabId: String?): PageContent? {
        val provider = activeTabsProvider ?: return null
        runCatching { provider.refreshTabs() }
        val tabs: List<ActiveTabData> = provider.activeTabs.value
        if (tabs.isEmpty()) return null

        val candidate: ActiveTabData? = if (tabId != null) {
            tabs.firstOrNull { it.tabId == tabId }
        } else {
            val activePanel = provider.activePanelId
            val inActivePanel = if (activePanel != null) {
                tabs.filter { it.panelId == activePanel }
            } else {
                tabs
            }
            inActivePanel.firstOrNull { !it.url.isNullOrBlank() }
                ?: tabs.firstOrNull { !it.url.isNullOrBlank() }
        }
        if (candidate == null) return null
        if (candidate.url.isNullOrBlank()) return null

        val browser = runCatching { provider.getBrowserIntegration(candidate.tabId) }
            .getOrNull()
            ?: return null
        if (!browser.isBrowserAvailable()) return null

        val raw = runCatching { browser.executeJavaScript(JsExtractor.FULL_PAYLOAD) }
            .getOrNull()
            ?: return null

        return parse(raw)
    }

    private fun parse(raw: Any?): PageContent? {
        val element = when (raw) {
            is JsonElement -> raw
            is String -> runCatching { json.parseToJsonElement(raw) }.getOrNull()
            else -> runCatching { json.encodeToString(JsonElement.serializer(), JsonPrimitive(raw.toString())) }
                .getOrNull()
                ?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        } as? JsonObject ?: return null

        return runCatching {
            val url = element.string("url") ?: return@runCatching null
            val title = element.string("title") ?: ""
            val language = element.stringOrNull("language")
            val visibleText = element.string("visibleText") ?: ""
            val headings = element.jsonArrayOrEmpty("headings").mapNotNull { h ->
                val obj = h as? JsonObject ?: return@mapNotNull null
                val lvl = (obj["level"] as? JsonPrimitive)?.int ?: return@mapNotNull null
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                ai.rever.boss.plugin.dynamic.pagecontent.api.Heading(level = lvl, text = text)
            }
            val links = element.jsonArrayOrEmpty("links").mapNotNull { l ->
                val obj = l as? JsonObject ?: return@mapNotNull null
                val href = (obj["href"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull ?: ""
                val ext = (obj["isExternal"] as? JsonPrimitive)?.boolean ?: false
                ai.rever.boss.plugin.dynamic.pagecontent.api.PageLink(
                    href = href, text = text, isExternal = ext,
                )
            }
            val meta = element.jsonObjectOrNull("meta")?.let { mo ->
                buildMap<String, String> {
                    for ((k, v) in mo) {
                        val s = (v as? JsonPrimitive)?.contentOrNull ?: continue
                        put(k, s)
                    }
                }
            } ?: emptyMap()
            val wordCount = (element["wordCount"] as? JsonPrimitive)?.int ?: 0
            val readingTime = (element["readingTimeMinutes"] as? JsonPrimitive)?.int
                ?: ((wordCount + 199) / 200).coerceAtLeast(0)
            val screenshot = (element["screenshotBase64"] as? JsonPrimitive)?.contentOrNull
            PageContent(
                url = url,
                title = title,
                language = language,
                visibleText = visibleText,
                headings = headings,
                links = links,
                meta = meta,
                wordCount = wordCount,
                readingTimeMinutes = readingTime,
                screenshotBase64 = screenshot,
            )
        }.getOrNull()
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringOrNull(key: String): String? {
    val v = this[key] as? JsonPrimitive ?: return null
    return v.contentOrNull
}

private fun JsonObject.jsonArrayOrEmpty(key: String): List<JsonElement> =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList()

private fun JsonObject.jsonObjectOrNull(key: String): JsonObject? =
    this[key] as? JsonObject
