package ai.rever.boss.plugin.dynamic.pagecontent

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText

/**
 * Describes the Page Content panel: id, sidebar icon, default slot.
 *
 * Lives on the right sidebar (bottom slot) so it can sit below the Flow
 * Bridge panel when both are enabled, and is reached at priority 22
 * (Flow Bridge is 21).
 */
object PageContentInfo : PanelInfo {
    override val id = PanelId("page-content", 22)
    override val displayName = "Page Content"
    override val icon = FeatherIcons.FileText
    override val defaultSlotPosition: Panel = right.bottom
}
