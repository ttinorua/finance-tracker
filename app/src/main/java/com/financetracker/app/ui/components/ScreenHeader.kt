package com.financetracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.financetracker.app.util.Formatters
import com.financetracker.app.util.PeriodOption

/**
 * The header every main screen shares (Home's style): a small line over a big title, round
 * buttons on the right, and the AI orb last. [onBack] adds a back arrow for screens opened from
 * another one (Trends).
 */
@Composable
fun ScreenHeader(
    smallLine: String,
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenAskAi: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = if (onBack == null) 4.dp else 0.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                smallLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
        if (onOpenAskAi != null) AiOrb(42.dp, contentDescription = "AI advisor", onClick = onOpenAskAi)
    }
}

/** A round 40 dp header button, like Home's Trends button. */
@Composable
fun HeaderButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

/** The header title for a period: this month's name, otherwise the period's own label. */
fun periodTitle(option: PeriodOption): String =
    if (option == PeriodOption.THIS_MONTH) Formatters.month(System.currentTimeMillis()) else option.label
