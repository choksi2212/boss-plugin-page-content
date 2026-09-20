# AGENTS.md

Guidance for coding agents working on this repository.

## What this plugin is

A BOSS Console dynamic plugin that exposes **structured page content** to every other plugin (through a new `PageContentProvider` plugin API) and to in-terminal agents (through five `page_content_*` MCP tools).

## Layout

```
src/main/kotlin/ai/rever/boss/plugin/dynamic/pagecontent/
  api/
    PageContent.kt              - data classes + PageContentProvider interface
  JsExtractor.kt                - JS payload run inside the browser tab
  PageContentDynamicPlugin.kt   - entry point; registers API, panel, MCP tools
  PageContentProviderImpl.kt    - implementation; runs the JS extractor
  PageContentInfo.kt            - panel id and slot
  PageContentComponent.kt       - Decompose component owning the panel UI state
  PageContentContent.kt         - Composable surface (header, summary, extractor)
  PageContentMcpTools.kt        - five MCP tool definitions + handlers
  ProjectCompanionBridge.kt     - project-companion lookup via McpToolRegistry
src/main/resources/META-INF/boss-plugin/
  plugin.json                   - manifest; type "mixed"; out-of-process + fallback
```

## Plugin-to-plugin API lives here, not in the host jar

`PageContentProvider` is defined inside this plugin's source (`api/PageContent.kt`) rather than in `boss-plugin-api`. Plugins consume it through `context.getPluginAPI(PageContentProvider::class.java)`, which the host types as `<T : Any>`. Adding a member to a host-typed interface like `PluginContext` would require a host release; this one ships with the plugin.

## Extraction model

A single JS payload (`JsExtractor.FULL_PAYLOAD`) is run inside the browser tab through `BrowserIntegration.executeJavaScript`. The payload returns a JSON object that mirrors the `PageContent` shape; the implementation parses it in Kotlin and emits typed values.

Per-element CSS extraction runs in the panel (`PageContentComponent.runSelector`); the MCP `page_content_extract` tool falls back to slicing the already-extracted visible text so it always returns something for callers that do not have a browser integration handy.

## Why the screenshot is optional

`PageContent.screenshotBase64` is a string of unknown size - a typical page snapshot can be hundreds of kilobytes to several megabytes. The MCP tool omits it by default; consumers ask for it with `with_screenshot=true`. The plugin API consumer receives it whenever the host can produce one (the implementation leaves the field null when it cannot).

## Why the JS extractor uses one payload

`executeJavaScript` round-trips through the host. Running several smaller scripts in a row to gather each field is slower and re-orders independently; one payload keeps the result consistent.

## Project companion lookup, no hard dep

The panel's "Save as note" button calls `project_companion_save_note` through `McpToolRegistry.invoke`. The plugin never names the project companion plugin id; if it isn't loaded, the button falls back to the clipboard. No hard dependency is declared in `plugin.json`, so uninstalling the project companion plugin does not disable this one.

## Build

```bash
./gradlew buildPluginJar -x test --no-daemon
```

Local builds read the api jar from `../boss-plugin-api/build/libs/boss-plugin-api-1.0.93.jar`. CI sets `CI=true` and reads it from `build/downloaded-deps/boss-plugin-api.jar` after downloading a matching release.

## CI

`.github/workflows/build.yml` runs on push to `main` and delegates to the upstream release workflow. `.github/workflows/test.yml` runs `./gradlew build` on every pull request; `build` depends on `buildPluginJar`, so a packaging break is caught here, not at release.

## Style

- Kotlin files end with a newline.
- Prose uses spaced hyphens (` - `), never em-dashes (U+2014).
- ktlint and detekt are not yet wired into this repo; rely on the same style as `boss-plugin-fluck-research`.

## What deliberately stays out of scope

- A generic JS `page_content_evaluate` - the structured extraction is the contract; raw eval would expose a much larger attack surface and is reserved for a future release if a host gate appears.
- A hard dependency on the browser plugin. The plugin works without it - it just returns null from `current()` and a "no browser tab" error from the MCP tools.
- Persistent storage of the rendered note. The clipboard is the fallback; per-host storage policy is the host's to decide.
