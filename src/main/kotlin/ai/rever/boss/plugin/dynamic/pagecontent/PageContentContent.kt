package ai.rever.boss.plugin.dynamic.pagecontent

import ai.rever.boss.plugin.dynamic.pagecontent.api.PageContent
import ai.rever.boss.plugin.dynamic.pagecontent.api.PageLink
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Composable surface for the Page Content panel.
 *
 * Renders a header with refresh, a status/error toast row, the current
 * page's title/URL/headings/links/word-count, and an extractor block
 * (selector + "Extract" button + extracted text preview). Actions
 * (copy markdown, save as note) sit at the bottom.
 */
@Composable
fun PageContentContent(component: PageContentComponent) {
    BossTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeaderRow(onRefresh = { component.refresh() })

                Divider(color = BossThemeColors.TextPrimary.copy(alpha = 0.1f))

                val status by component.status.collectAsState()
                val error by component.error.collectAsState()
                Toast(status = status, error = error, onDismiss = { component.clearMessages() })

                // Hoisted so the LazyColumn body (a LazyListScope) only sees
                // plain values - it is not a @Composable context.
                val content by component.content.collectAsState()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { CurrentPageSection(content) }
                    item { ExtractorSection(component) }
                    item { ActionsSection(component) }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colors.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Page Content",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun Toast(
    status: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    if (status == null && error == null) return
    LaunchedEffect(status, error) {
        delay(4000)
        onDismiss()
    }
    val message = error ?: status ?: return
    val isError = error != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isError) BossThemeColors.ErrorColor else BossThemeColors.SuccessColor,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isError) Icons.Default.Error else Icons.Default.Check,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = BossThemeColors.TextPrimary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = message,
            fontSize = 11.sp,
            color = BossThemeColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                modifier = Modifier.size(12.dp),
                tint = BossThemeColors.TextPrimary.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun CurrentPageSection(content: PageContent?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colors.surface,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = content?.title?.takeIf { it.isNotBlank() } ?: "(no active browser tab)",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = content?.url.orEmpty(),
            fontSize = 11.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (content != null) {
            val c = content
            Text(
                text = buildString {
                    if (!c.language.isNullOrBlank()) {
                        append("Language: ").append(c.language).append(" - ")
                    }
                    append("Words: ").append(c.wordCount)
                        .append(" - Reading time: ").append(c.readingTimeMinutes).append(" min")
                },
                fontSize = 10.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )

            if (c.meta.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Meta",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    c.meta.entries.take(8).forEach { (k, v) ->
                        Text(
                            text = "$k: ${v.take(120)}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (c.meta.size > 8) {
                        Text(
                            text = "... +${c.meta.size - 8} more",
                            fontSize = 10.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            if (c.headings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Headings",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    c.headings.take(20).forEach { h ->
                        Text(
                            text = "${"  ".repeat(h.level.coerceAtLeast(1) - 1)}- ${h.text}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (c.links.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Top links",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    c.links.take(20).forEach { link ->
                        LinkRow(link)
                    }
                    if (c.links.size > 20) {
                        Text(
                            text = "... +${c.links.size - 20} more",
                            fontSize = 10.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(link: PageLink) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = link.text.takeIf { it.isNotBlank() } ?: link.href,
            fontSize = 10.sp,
            color = if (link.isExternal) BossThemeColors.AccentColor
                else MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExtractorSection(component: PageContentComponent) {
    val selector by component.selector.collectAsState()
    val extraction by component.extraction.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colors.surface,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Extract by CSS selector",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.85f),
        )
        OutlinedTextField(
            value = selector,
            onValueChange = { component.setSelector(it) },
            placeholder = {
                Text(
                    text = "e.g. article p, .summary, h1",
                    fontSize = 11.sp,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = MaterialTheme.colors.onSurface,
                cursorColor = BossThemeColors.AccentColor,
            ),
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { component.extractBySelector() },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = BossThemeColors.AccentColor,
                    contentColor = BossThemeColors.TextPrimary,
                ),
            ) {
                Text(text = "Extract", fontSize = 11.sp)
            }
        }
        if (!extraction.isNullOrBlank()) {
            val text = extraction ?: ""
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colors.background,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(8.dp),
            ) {
                Text(
                    text = text,
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActionsSection(component: PageContentComponent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = { component.copyMarkdown() }) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Copy markdown", fontSize = 11.sp)
        }
        TextButton(onClick = { component.saveAsNote() }) {
            Icon(
                imageVector = Icons.Default.NoteAdd,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Save as note", fontSize = 11.sp)
        }
    }
}
