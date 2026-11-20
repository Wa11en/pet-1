package ru.privatediary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.privatediary.R
import ru.privatediary.domain.models.TrashedEntry
import ru.privatediary.domain.models.TrashPolicy

@Composable
internal fun TrashScreen(
    entries: List<TrashedEntry>,
    busy: Boolean,
    onBack: () -> Unit,
    onRestore: (Long) -> Unit,
    onDeleteForever: (Long) -> Unit,
) {
    var deleting by remember { mutableStateOf<TrashedEntry?>(null) }
    BackHandler { if (!busy) onBack() }
    LaunchedEffect(entries) {
        if (deleting != null && entries.none { it.entry.id == deleting?.entry?.id }) deleting = null
    }
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedIconButton(onClick = onBack, enabled = !busy, modifier = Modifier.size(48.dp),
                        shape = CircleShape, border = BorderStroke(1.dp, colors.outlineVariant)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), Modifier.size(21.dp))
                    }
                    Text(stringResource(R.string.trash_title), style = MaterialTheme.typography.headlineMedium)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "description") {
                Text(stringResource(R.string.trash_description),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
            if (entries.isEmpty()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.padding(24.dp).size(32.dp), tint = colors.primary)
                    }
                    Text(stringResource(R.string.trash_empty), style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center)
                    Text(stringResource(R.string.trash_empty_hint), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
            items(entries, key = { it.entry.id }) { item ->
                val days = TrashPolicy.remainingDays(item.deletedAt, System.currentTimeMillis())
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLowest),
                    border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f))) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(formatDateTime(item.entry.timestamp), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                        Text(item.entry.text, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 5, overflow = TextOverflow.Ellipsis)
                        Text(pluralStringResource(R.plurals.trash_days_left, days, days),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = { onRestore(item.entry.id) }, enabled = !busy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.trash_restore))
                            }
                            IconButton(onClick = { deleting = item }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.DeleteForever, stringResource(R.string.trash_delete_forever),
                                    tint = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            icon = { Icon(Icons.Outlined.DeleteForever, null) },
            title = { Text(stringResource(R.string.trash_delete_forever_title)) },
            text = { Text(stringResource(R.string.trash_delete_forever_explanation)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; onDeleteForever(item.entry.id) }, enabled = !busy) {
                    Text(stringResource(R.string.trash_delete_forever), color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, enabled = !busy) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
