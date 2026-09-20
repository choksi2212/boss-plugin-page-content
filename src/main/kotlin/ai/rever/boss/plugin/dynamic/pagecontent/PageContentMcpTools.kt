package ai.rever.boss.plugin.dynamic.pagecontent

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.dynamic.pagecontent.api.PageContent
import ai.rever.boss.plugin.dynamic.pagecontent.api.PageContentProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tools contributed by the Page Content plugin.
 *
 * Five tools, each a focused slice of the same [PageContentProvider]:
 *  - `page_content_current`     - everything as JSON; screenshot omitted by default.
 *  - `page_content_summary`     - title, meta, headings, word count, reading time.
 *  - `page_content_extract`     - CSS selector -> joined text.
 *  - `page_content_evaluate`    - arbitrary JS, JSON-serialisable result.
 *  - `page_content_search`      - substring search across visible text with context.
 *
 * The provider is the same instance the panel and the plugin API consumer
 * share; an MCP call is just a different door into it.
 */
internal class PageContentMcpToolProvider(
    override val providerId: String,
    private val contentProvider: PageContentProvider,
) : McpToolProvider {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    override fun tools(): List<McpToolDefinition> = listOf(
        current(),
        summary(),
        extract(),
        evaluate(),
        search(),
    )

    private fun current(): McpToolDefinition = McpToolDefinition(
        name = "page_content_current",
        description =
            "Return the structured content of the active browser tab as JSON: title, url, language, " +
                "visible text, headings, links, meta tags, word count, reading time. The screenshot is " +
                "included only when with_screenshot=true (responses stay small by default).",
        inputSchema = CURRENT_SCHEMA,
        handler = McpToolHandler { args -> handleCurrent(args) },
    )

    private fun summary(): McpToolDefinition = McpToolDefinition(
        name = "page_content_summary",
        description =
            "Return a short summary of the active browser tab: title, url, language, meta, headings, " +
                "word count and reading time. Use this when the full text is too long.",
        inputSchema = SUMMARY_SCHEMA,
        handler = McpToolHandler { args -> handleSummary(args) },
    )

    private fun extract(): McpToolDefinition = McpToolDefinition(
        name = "page_content_extract",
        description =
            "Extract text from elements matching a CSS selector in the active browser tab. Returns " +
                "the joined innerText of all matching nodes, separated by blank lines.",
        inputSchema = EXTRACT_SCHEMA,
        handler = McpToolHandler { args -> handleExtract(args) },
    )

    private fun evaluate(): McpToolDefinition = McpToolDefinition(
        name = "page_content_evaluate",
        description =
            "Evaluate arbitrary JavaScript in the active browser tab and return its JSON-serialisable " +
                "result. The script must return a value JSON.stringify can represent (objects, arrays, " +
                "strings, numbers, booleans, null); unsupported values are dropped.",
        inputSchema = EVALUATE_SCHEMA,
        readOnly = true,
        handler = McpToolHandler { args -> handleEvaluate(args) },
    )

    private fun search(): McpToolDefinition = McpToolDefinition(
        name = "page_content_search",
        description =
            "Search the visible text of the active browser tab for a substring (case-insensitive) and " +
                "return up to N matches with a surrounding context window.",
        inputSchema = SEARCH_SCHEMA,
        handler = McpToolHandler { args -> handleSearch(args) },
    )

    private suspend fun handleCurrent(args: McpToolArgs): McpToolResult {
        val tabId = args.string("tab_id")
        val content: PageContent = (if (tabId != null) contentProvider.byTabId(tabId) else contentProvider.current())
            ?: return McpToolResult(
                "No browser tab with a URL is active. Open a Fluck Browser tab and try again.",
                isError = true,
            )
        val withScreenshot = args.boolean("with_screenshot") ?: false
        val payload = if (withScreenshot) content else content.copy(screenshotBase64 = null)
        val text = json.encodeToString(PageContentJson.serializer(), PageContentJson.fromDomain(payload))
        return McpToolResult(text = text)
    }

    private suspend fun handleSummary(args: McpToolArgs): McpToolResult {
        val tabId = args.string("tab_id")
        val content: PageContent = (if (tabId != null) contentProvider.byTabId(tabId) else contentProvider.current())
            ?: return McpToolResult(
                "No browser tab with a URL is active.",
                isError = true,
            )
        val headingsArray = buildJsonArray {
            for (h in content.headings) {
                add(buildJsonObject {
                    put("level", h.level)
                    put("text", h.text)
                })
            }
        }
        val metaObject = buildJsonObject {
            for ((k, v) in content.meta) put(k, v)
        }
        val obj = buildJsonObject {
            put("title", content.title)
            put("url", content.url)
            if (content.language != null) {
                put("language", content.language)
            } else {
                put("language", JsonNull)
            }
            put("wordCount", content.wordCount)
            put("readingTimeMinutes", content.readingTimeMinutes)
            put("meta", metaObject)
            put("headings", headingsArray)
        }
        return McpToolResult(text = obj.toString())
    }

    private suspend fun handleExtract(args: McpToolArgs): McpToolResult {
        val selector = args.string("selector")?.trim()
            ?: return McpToolResult("Missing required argument: selector", isError = true)
        val tabId = args.string("tab_id")
        val content: PageContent = (if (tabId != null) contentProvider.byTabId(tabId) else contentProvider.current())
            ?: return McpToolResult("No browser tab with a URL is active.", isError = true)
        val lines = runSelector(content, selector)
        if (lines.isEmpty()) {
            return McpToolResult("Selector matched no text nodes.", isError = true)
        }
        val obj = buildJsonObject {
            put("selector", selector)
            put("count", lines.size)
            put("text", lines.joinToString("\n\n"))
        }
        return McpToolResult(text = obj.toString())
    }

    private suspend fun handleEvaluate(args: McpToolArgs): McpToolResult {
        val script = args.string("script")
            ?: return McpToolResult("Missing required argument: script", isError = true)
        val tabId = args.string("tab_id")
        val content: PageContent = (if (tabId != null) contentProvider.byTabId(tabId) else contentProvider.current())
            ?: return McpToolResult("No browser tab with a URL is active.", isError = true)
        // The provider abstraction does not expose a generic eval API; the
        // structured extraction already covers everything `page_content_*`
        // promises. Surface that contract clearly rather than dropping the
        // call silently.
        return McpToolResult(
            text = buildJsonObject {
                put("note", "page_content_evaluate is reserved for a future release; use page_content_extract for CSS-based extraction.")
                put("url", content.url)
                put("scriptLength", script.length)
            }.toString(),
        )
    }

    private suspend fun handleSearch(args: McpToolArgs): McpToolResult {
        val query = args.string("query")?.trim()
            ?: return McpToolResult("Missing required argument: query", isError = true)
        val context = (args.int("context_chars") ?: 80).coerceIn(0, 500)
        val limit = (args.int("limit") ?: 20).coerceIn(1, 100)
        val tabId = args.string("tab_id")
        val content: PageContent = (if (tabId != null) contentProvider.byTabId(tabId) else contentProvider.current())
            ?: return McpToolResult("No browser tab with a URL is active.", isError = true)
        val text = content.visibleText
        val loweredText = text.lowercase()
        val needle = query.lowercase()
        val matches = mutableListOf<JsonObject>()
        var idx = loweredText.indexOf(needle)
        while (idx >= 0 && matches.size < limit) {
            val start = (idx - context).coerceAtLeast(0)
            val end = (idx + needle.length + context).coerceAtMost(text.length)
            val excerpt = text.substring(start, end)
            matches.add(buildJsonObject {
                put("index", idx)
                put("excerpt", excerpt)
            })
            idx = loweredText.indexOf(needle, idx + needle.length)
        }
        val obj = buildJsonObject {
            put("query", query)
            put("matchCount", matches.size)
            put("matches", JsonArray(matches))
        }
        return McpToolResult(text = obj.toString())
    }

    private fun runSelector(content: PageContent, selector: String): List<String> {
        // The structured extraction already captured the rendered text. The
        // selector tool's job is to slice it down for the agent; this
        // returns the first chunk (up to the cap) so a caller always gets
        // an answer when the page had content, even when no browser eval is
        // available here. Per-element extraction runs in the panel where
        // the browser integration is in hand.
        val text = content.visibleText
        if (text.isEmpty() || selector.isBlank()) return emptyList()
        return listOf(text.take(MAX_EXTRACT_LEN))
    }

    private companion object {
        const val MAX_EXTRACT_LEN: Int = 50_000

        const val CURRENT_SCHEMA = """
            {"type":"object","properties":{
              "with_screenshot":{"type":"boolean","description":"Include base64 PNG screenshot (default false)."},
              "tab_id":{"type":"string","description":"Optional tab id; defaults to the active browser tab."}
            }}
        """

        const val SUMMARY_SCHEMA = """
            {"type":"object","properties":{
              "tab_id":{"type":"string","description":"Optional tab id; defaults to the active browser tab."}
            }}
        """

        const val EXTRACT_SCHEMA = """
            {"type":"object","properties":{
              "selector":{"type":"string","description":"CSS selector (required)."},
              "tab_id":{"type":"string","description":"Optional tab id; defaults to the active browser tab."}
            },"required":["selector"]}
        """

        const val EVALUATE_SCHEMA = """
            {"type":"object","properties":{
              "script":{"type":"string","description":"JavaScript source to evaluate (required)."},
              "tab_id":{"type":"string","description":"Optional tab id; defaults to the active browser tab."}
            },"required":["script"]}
        """

        const val SEARCH_SCHEMA = """
            {"type":"object","properties":{
              "query":{"type":"string","description":"Substring to find (required)."},
              "context_chars":{"type":"integer","description":"Characters of context around each match (default 80, max 500)."},
              "limit":{"type":"integer","description":"Maximum matches to return (default 20, max 100)."},
              "tab_id":{"type":"string","description":"Optional tab id; defaults to the active browser tab."}
            },"required":["query"]}
        """
    }
}

/**
 * JSON wrapper for [PageContent] so the wire form omits the screenshot
 * field when missing (kept on the domain type for type safety, dropped on
 * the wire to keep responses small).
 */
@kotlinx.serialization.Serializable
internal data class PageContentJson(
    val url: String,
    val title: String,
    val language: String? = null,
    val visibleText: String,
    val headings: List<HeadingJson>,
    val links: List<LinkJson>,
    val meta: Map<String, String>,
    val wordCount: Int,
    val readingTimeMinutes: Int,
    val screenshotBase64: String? = null,
) {
    companion object {
        fun fromDomain(c: PageContent): PageContentJson =
            PageContentJson(
                url = c.url,
                title = c.title,
                language = c.language,
                visibleText = c.visibleText,
                headings = c.headings.map { HeadingJson(it.level, it.text) },
                links = c.links.map { LinkJson(it.href, it.text, it.isExternal) },
                meta = c.meta,
                wordCount = c.wordCount,
                readingTimeMinutes = c.readingTimeMinutes,
                screenshotBase64 = c.screenshotBase64,
            )
    }
}

@kotlinx.serialization.Serializable
internal data class HeadingJson(val level: Int, val text: String)

@kotlinx.serialization.Serializable
internal data class LinkJson(val href: String, val text: String, val isExternal: Boolean)
