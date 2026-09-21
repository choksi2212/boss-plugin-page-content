package ai.rever.boss.plugin.dynamic.pagecontent

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.dynamic.pagecontent.api.PageContentProvider

/**
 * Page Content dynamic plugin - Loaded from external JAR.
 *
 * Exposes structured page content to every other BOSS plugin via the new
 * [PageContentProvider] plugin API and to agents through the
 * `page_content_*` MCP tool set.
 *
 * Plugin-to-plugin reach goes through `context.registerPluginAPI(...)`;
 * the published instance is the same [PageContentProviderImpl] the panel
 * and the MCP tools share. Registering one implementation means a single
 * browser tab read satisfies both consumers.
 *
 * The new interface lives in this plugin's own source rather than the
 * host API jar so it can ship without a host release.
 */
class PageContentDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.pagecontent"
    override val displayName: String = "Page Content"
    override val version: String = manifestVersion()
    override val description: String =
        "Structured page content (text, headings, links, screenshot) for BOSS plugins and agents, via a new PageContentProvider plugin API"
    override val author: String = "choksi2212"
    override val url: String = "https://github.com/choksi2212/boss-plugin-page-content"

    private var providerImpl: PageContentProviderImpl? = null

    override fun register(context: PluginContext) {
        val provider = PageContentProviderImpl(context.activeTabsProvider)
        providerImpl = provider

        // Publish for other plugins; getPluginAPI(PageContentProvider::class.java) finds it.
        context.registerPluginAPI(provider as PageContentProvider)

        // The sidebar panel for human use.
        context.panelRegistry.registerPanel(PageContentInfo) { ctx, panelInfo ->
            PageContentComponent(
                ctx = ctx,
                panelInfo = panelInfo,
                provider = provider,
                activeTabsProvider = context.activeTabsProvider,
                clipboardProvider = context.clipboardProvider,
            )
        }

        // The MCP tool surface for in-terminal agents.
        context.registerMcpToolProvider(
            PageContentMcpToolProvider(
                providerId = pluginId,
                contentProvider = provider,
            )
        )
    }

    override fun dispose() {
        providerImpl = null
    }

    /**
     * The version from this plugin's own manifest.
     *
     * Every BOSS plugin ships `/META-INF/boss-plugin/plugin.json` at the
     * same resource path, so a `getResourceAsStream` that returns the
     * first hit could read someone else's manifest if the host ever loads
     * plugins through a parent-first classloader. Only the entry that
     * names this plugin id is accepted.
     */
    private fun manifestVersion(): String =
        runCatching {
            javaClass.classLoader
                ?.getResources("META-INF/boss-plugin/plugin.json")
                ?.asSequence()
                ?.mapNotNull { url -> runCatching { url.readText() }.getOrNull() }
                ?.firstOrNull { text -> field(text, "pluginId") == pluginId }
                ?.let { text -> field(text, "version") }
        }.getOrNull() ?: "unknown"

    private fun field(manifest: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
}
