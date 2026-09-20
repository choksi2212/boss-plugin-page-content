# Page Content Plugin for BOSS Console

A BOSS Console plugin that exposes the **structured content** of the page the user is reading in the integrated browser - title, URL, language, visible text, headings, links, meta tags, word count, reading time, and (optionally) a base64-encoded screenshot.

Two surfaces:

- **A new plugin-to-plugin API**, `PageContentProvider`, that any installed BOSS plugin can resolve through `context.getPluginAPI(PageContentProvider::class.java)`.
- **Five MCP tools**, `page_content_*`, that an in-terminal agent can call directly.

A small sidebar panel is bundled for human use.

## Why it exists

Before this plugin, the only way to read structured content out of the BOSS integrated browser was `browser_run_js` - raw JavaScript execution that every plugin had to roll its own extraction on top of, with its own discovery of the active tab, its own tab-id handling, and its own failure modes when the browser plugin is not loaded.

This plugin provides one canonical answer for the common case: *what does the current page say?*

## Install

1. Download `boss-plugin-page-content-0.1.0.jar` from a release.
2. Open BOSS Console.
3. Open the **Toolbox** (Plugin Manager).
4. Install from local jar and enable.

`minBossVersion` is 9.4.2; `apiVersion` and `minApiVersion` are 1.0.93.

## Consume `PageContentProvider` from another plugin

The interface lives in this plugin's source so it can ship without a host API release. Reach it through `getPluginAPI`, which the host provides; no compile-time dependency is required.

```kotlin
class MyReadingPlugin : DynamicPlugin {
    override fun register(context: PluginContext) {
        val provider = context.getPluginAPI(PageContentProvider::class.java)
        context.panelRegistry.registerPanel(MyPanelInfo) { ctx, panelInfo ->
            MyComponent(ctx, panelInfo, provider)
        }
    }
}

class MyComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val provider: PageContentProvider?,
) : PanelComponentWithUI, ComponentContext by ctx {

    @Composable
    override fun Content() {
        var title by remember { mutableStateOf("(no page yet)") }
        LaunchedEffect(Unit) {
            // Suspend - returns null when no browser tab is active.
            val page = provider?.current()
            if (page != null) {
                title = page.title
            }
        }
        Text(title)
    }
}
```

`current()` and `byTabId(tabId)` are `suspend` and return `null` when no browser tab is available. `observe()` returns a `Flow<PageContent?>` that emits when the URL or title changes.

## MCP tools

| Name | What it does |
|---|---|
| `page_content_current` | Returns the full PageContent JSON. Screenshot is omitted unless `with_screenshot=true`. |
| `page_content_summary` | Title, URL, language, meta, headings, word count, reading time. |
| `page_content_extract` | Runs a CSS selector in the active tab and returns the joined text of matching nodes. |
| `page_content_evaluate` | Reserved for a future release - the structured extraction is the supported path. |
| `page_content_search` | Case-insensitive substring search across the visible text, with a configurable context window. |

All five accept an optional `tab_id` to target a specific browser tab instead of the active one.

## Panel

The panel renders the active page's title, URL, language, meta tags, headings, and top 20 links, plus a CSS-selector extractor and two actions:

- **Copy markdown** - puts a markdown rendering of the page on the clipboard.
- **Save as note** - calls the project companion plugin's `project_companion_save_note` MCP tool when it is loaded, falling back to the clipboard when it is not.

## Compatibility

- BOSS API: 1.0.93
- BOSS host: 9.4.2 or newer
- Platforms: out-of-process with in-process fallback
- Browser dependency: requires the integrated browser to be loaded for live extraction; degrades to "no browser tab is active" when it is not

## License

Apache 2.0. See `LICENSE` if present in the release artifact, or the upstream BOSS Console plugin SDK licensing terms.
