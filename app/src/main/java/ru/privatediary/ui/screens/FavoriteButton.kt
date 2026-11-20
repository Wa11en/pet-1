package ru.privatediary.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.privatediary.R
import ru.privatediary.domain.models.Entry

@Composable
internal fun FavoriteButton(entry: Entry, enabled: Boolean = true, onToggle: (Long) -> Unit) {
    IconButton(onClick = { onToggle(entry.id) }, enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { selected = entry.isFavorite }) {
        Icon(if (entry.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            stringResource(if (entry.isFavorite) R.string.favorite_remove else R.string.favorite_add),
            tint = if (entry.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
