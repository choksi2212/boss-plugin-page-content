package ai.rever.boss.plugin.dynamic.pagecontent

/**
 * The JavaScript payload executed inside the browser tab to extract
 * structured content.
 *
 * The host's `BrowserIntegration.executeJavaScript` returns a JSON-serializable
 * object, so this script returns a single object whose shape mirrors
 * [ai.rever.boss.plugin.dynamic.pagecontent.api.PageContent]. Keeping it in a
 * single executeJavaScript call avoids the round-trip cost and ordering
 * problems of running several smaller ones.
 *
 * The text extraction is a readability-lite approach: walk text-bearing
 * elements, score them by density and link noise, and return the densest
 * cluster. It is not as good as Mozilla Readability for pathological pages,
 * but is good enough for the common case and ships without a dependency.
 */
object JsExtractor {
    val FULL_PAYLOAD: String = """
        (function() {
            try {
                var doc = document;
                var root = doc.documentElement;
                var body = doc.body || root;

                var title = (doc.title || '').trim();
                var url = location.href || '';
                var language = (root && root.getAttribute) ? root.getAttribute('lang') : null;

                var headings = [];
                var hSelector = 'h1,h2,h3,h4,h5,h6';
                var hNodes = doc.querySelectorAll(hSelector);
                for (var i = 0; i < hNodes.length; i++) {
                    var h = hNodes[i];
                    var lvl = parseInt(h.tagName.substring(1), 10);
                    var text = (h.innerText || h.textContent || '').trim();
                    if (text.length === 0) continue;
                    if (headings.length < 500) {
                        headings.push({ level: lvl, text: text.substring(0, 500) });
                    }
                }

                var meta = {};
                var metaNodes = doc.querySelectorAll('meta[name],meta[property]');
                for (var j = 0; j < metaNodes.length; j++) {
                    var m = metaNodes[j];
                    var key = m.getAttribute('name') || m.getAttribute('property');
                    var val = m.getAttribute('content');
                    if (!key || !val) continue;
                    if (meta[key] !== undefined) continue;
                    meta[key] = val.substring(0, 1000);
                }

                var links = [];
                var aNodes = doc.querySelectorAll('a[href]');
                var seen = {};
                for (var k = 0; k < aNodes.length; k++) {
                    if (links.length >= 500) break;
                    var a = aNodes[k];
                    var href = a.getAttribute('href') || '';
                    if (!href || href.indexOf('javascript:') === 0) continue;
                    if (href.indexOf('#') === 0 && href.length < 2) continue;
                    var anchorText = (a.innerText || a.textContent || '').trim();
                    if (anchorText.length > 200) anchorText = anchorText.substring(0, 200);
                    var abs;
                    try { abs = new URL(href, location.href).href; } catch (e) { continue; }
                    var sig = abs + '|' + anchorText;
                    if (seen[sig]) continue;
                    seen[sig] = true;
                    var isExternal = false;
                    try {
                        isExternal = new URL(abs).host !== location.host;
                    } catch (e) { isExternal = false; }
                    links.push({ href: abs, text: anchorText, isExternal: isExternal });
                }

                var text = extractReadableText(body);
                var words = countWords(text);

                var shot = null;
                try {
                    if (typeof window !== 'undefined' && window.__pageContentScreenshot) {
                        shot = window.__pageContentScreenshot;
                    }
                } catch (e) { /* swallow */ }

                return {
                    url: url,
                    title: title,
                    language: language || null,
                    visibleText: text,
                    headings: headings,
                    links: links,
                    meta: meta,
                    wordCount: words,
                    readingTimeMinutes: Math.max(1, Math.round(words / 200)),
                    screenshotBase64: shot
                };
            } catch (err) {
                return {
                    url: location.href || '',
                    title: (document.title || '').trim(),
                    language: (document.documentElement && document.documentElement.getAttribute)
                        ? document.documentElement.getAttribute('lang') : null,
                    visibleText: '',
                    headings: [],
                    links: [],
                    meta: {},
                    wordCount: 0,
                    readingTimeMinutes: 0,
                    screenshotBase64: null,
                    error: (err && err.message) ? String(err.message) : 'extraction failed'
                };
            }

            function extractReadableText(root) {
                var SKIP = { SCRIPT:1, STYLE:1, NOSCRIPT:1, IFRAME:1, SVG:1, CANVAS:1,
                    TEMPLATE:1, HEADER:1, FOOTER:1, NAV:1, ASIDE:1, FORM:1, BUTTON:1 };
                var candidates = root.querySelectorAll('article, main, section, div, p');
                var best = { score: -1, text: '' };
                for (var i = 0; i < candidates.length; i++) {
                    var c = candidates[i];
                    var raw = (c.innerText || c.textContent || '');
                    if (!raw) continue;
                    var stripped = stripInlineTags(c);
                    if (stripped.length < 60) continue;
                    var score = scoreText(stripped, c);
                    if (score > best.score) best = { score: score, text: stripped };
                }
                if (!best.text) {
                    return stripInlineTags(root).trim();
                }
                return best.text.trim();
            }

            function stripInlineTags(node) {
                var SKIP_LOCAL = { SCRIPT:1, STYLE:1, NOSCRIPT:1, IFRAME:1, SVG:1, CANVAS:1, TEMPLATE:1 };
                var out = [];
                walk(node, out, SKIP_LOCAL);
                return collapseWhitespace(out.join(' '));
            }

            function walk(node, out, skip) {
                if (!node) return;
                if (node.nodeType === 3) {
                    var t = node.nodeValue;
                    if (t) out.push(t);
                    return;
                }
                if (node.nodeType !== 1) return;
                var tag = (node.tagName || '').toUpperCase();
                if (skip[tag]) return;
                var BLOCKS = { P:1, BR:1, LI:1, H1:1, H2:1, H3:1, H4:1, H5:1, H6:1, DIV:1, SECTION:1,
                    ARTICLE:1, MAIN:1, BLOCKQUOTE:1, PRE:1, TR:1, TD:1, TH:1, HR:1 };
                if (BLOCKS[tag]) out.push('\n');
                var children = node.childNodes;
                for (var i = 0; i < children.length; i++) walk(children[i], out, skip);
                if (BLOCKS[tag]) out.push('\n');
            }

            function collapseWhitespace(s) {
                return s.replace(/[\t ]+/g, ' ').replace(/\n[ \t]+/g, '\n').replace(/\n{3,}/g, '\n\n');
            }

            function scoreText(text, node) {
                var len = text.length;
                if (len < 60) return -1;
                var links = node.querySelectorAll('a').length;
                var linkDensity = links / Math.max(1, len / 100);
                var commas = (text.match(/,/g) || []).length;
                return len * (1 - Math.min(0.5, linkDensity)) + commas * 20;
            }

            function countWords(text) {
                if (!text) return 0;
                var m = text.match(/[\p{L}\p{N}]+/gu);
                return m ? m.length : 0;
            }
        })();
    """.trimIndent()

    /**
     * A focused payload for [page_content_extract] - returns just the joined
     * text of elements matching a CSS selector, or an empty string if no
     * matches.
     */
    val EXTRACT_SELECTOR_PAYLOAD: String = """
        (function(sel) {
            try {
                if (!sel || typeof sel !== 'string') return '';
                var nodes = document.querySelectorAll(sel);
                if (!nodes || nodes.length === 0) return '';
                var parts = [];
                for (var i = 0; i < nodes.length; i++) {
                    var t = (nodes[i].innerText || nodes[i].textContent || '').trim();
                    if (t) parts.push(t);
                }
                return parts.join('\n\n');
            } catch (e) {
                return '';
            }
        })
    """.trimIndent()

    /**
     * A payload for [page_content_evaluate] - evaluates arbitrary JS in the
     * page context and returns the result serialised to JSON. Host APIs
     * expect a JSON-serializable object, so anything that does not survive
     * JSON.stringify will be lost; callers must wrap non-stringifying values.
     */
    val EVALUATE_PAYLOAD_PREFIX: String =
        "try { return (function(){ " +
            "try { return (function(){ "

    val EVALUATE_PAYLOAD_SUFFIX: String =
        " })(); } catch (e) { return { error: String((e && e.message) ? e.message : e) }; }" +
            " })(); } catch (e) { return { error: String((e && e.message) ? e.message : e) }; }"
}
