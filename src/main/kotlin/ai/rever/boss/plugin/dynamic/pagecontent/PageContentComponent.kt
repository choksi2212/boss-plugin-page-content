package ai.rever.boss.plugin.dynamic.pagecontent

import ai.rever.boss.plugin.api.ActiveTabsProvider
import ai.rever.boss.plugin.api.ClipboardProvider
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The live Page Content panel.
 *
 * Wraps a tiny ViewModel - the heavy lifting lives in
 * [PageContentProviderImpl], which is shared with the MCP tools. The
 * component holds the in-flight content, the CSS selector the user typed,
 * and the most recent status / error line.
 *
 * The component does not run a long-lived observer on `activeTabs` - the
 * browser plugin already publishes changes and a panel that responds to
 * every tab mutation would refresh while the user is mid-keystroke. A
 * manual refresh button and an initial load on first composition cover
 * the common cases.
 */
class PageContentComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val provider: PageContentProviderImpl,
    private val activeTabsProvider: ActiveTabsProvider?,
    private val clipboardProvider: ClipboardProvider?,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _content = MutableStateFlow<ai.rever.boss.plugin.dynamic.pagecontent.api.PageContent?>(null)
    val content: StateFlow<ai.rever.boss.plugin.dynamic.pagecontent.api.PageContent?> = _content.asStateFlow()

    private val _selector = MutableStateFlow("")
    val selector: StateFlow<String> = _selector.asStateFlow()

    private val _extraction = MutableStateFlow<String?>(null)
    val extraction: StateFlow<String?> = _extraction.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        scope.launch {
            try {
                val result = provider.refreshCurrent()
                _content.value = result
                if (result == null) {
                    _error.value = "No browser tab with a URL is active"
                }
            } catch (t: Throwable) {
                _error.value = "Refresh failed: ${t.message ?: t::class.simpleName}"
            }
        }
    }

    fun setSelector(text: String) {
        _selector.value = text
    }

    fun extractBySelector() {
        val sel = _selector.value.trim()
        if (sel.isBlank()) {
            _error.value = "Type a CSS selector first"
            return
        }
        scope.launch {
            try {
                val text = runSelector(sel)
                if (text.isNullOrBlank()) {
                    _extraction.value = null
                    _error.value = "Selector matched no text nodes"
                } else {
                    _extraction.value = text
                    _status.value = "Extracted ${text.length} chars"
                }
            } catch (t: Throwable) {
                _error.value = "Selector failed: ${t.message ?: t::class.simpleName}"
            }
        }
    }

    fun saveAsNote() {
        val current = _content.value
        if (current == null) {
            _error.value = "Nothing to save - refresh first"
            return
        }
        scope.launch {
            try {
                val bridge = ProjectCompanionBridge(
                    registry = null,
                    clipboard = clipboardProvider,
                )
                val msg = bridge.saveAsNote(current)
                _status.value = msg
            } catch (t: Throwable) {
                _error.value = "Save failed: ${t.message ?: t::class.simpleName}"
            }
        }
    }

    fun copyMarkdown() {
        val current = _content.value
        if (current == null) {
            _error.value = "Nothing to copy"
            return
        }
        val cp = clipboardProvider
        if (cp == null) {
            _error.value = "Clipboard provider unavailable"
            return
        }
        scope.launch {
            val md = renderMarkdown(current)
            if (cp.setText(md)) {
                _status.value = "Markdown copied to clipboard"
            } else {
                _error.value = "Clipboard write failed"
            }
        }
    }

    fun clearMessages() {
        _status.value = null
        _error.value = null
    }

    private suspend fun runSelector(selector: String): String? {
        val provider = activeTabsProvider ?: return null
        runCatching { provider.refreshTabs() }
        val tabs = provider.activeTabs.value
        val activePanel = provider.activePanelId
        val candidates = if (activePanel != null) {
            tabs.filter { it.panelId == activePanel }
        } else {
            tabs
        }
        val target = candidates.firstOrNull { !it.url.isNullOrBlank() }
            ?: tabs.firstOrNull { !it.url.isNullOrBlank() }
            ?: return null
        val browser = provider.getBrowserIntegration(target.tabId) ?: return null
        if (!browser.isBrowserAvailable()) return null
        val raw = browser.executeJavaScript(
            "(${JsExtractor.EXTRACT_SELECTOR_PAYLOAD})(${jsonStringLiteral(selector)})"
        ) ?: return null
        return when (raw) {
            is String -> raw
            else -> raw.toString()
        }
    }

    private fun jsonStringLiteral(s: String): String {
        // Hand-built JSON string literal: escape backslash and double-quote.
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        return "\"$escaped\""
    }

    @Composable
    override fun Content() {
        PageContentContent(component = this)
    }
}

private fun renderMarkdown(content: ai.rever.boss.plugin.dynamic.pagecontent.api.PageContent): String {
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
    sb.append("## Content").append('\n').append(content.visibleText).append('\n')
    return sb.toString()
}
