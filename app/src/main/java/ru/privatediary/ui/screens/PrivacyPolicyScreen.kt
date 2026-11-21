package ru.privatediary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.privatediary.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Native, locally bundled policy; opening it makes no external request. */
@Composable
internal fun PrivacyPolicyScreen(developerName: String, supportEmail: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val language = if (locale.language == "en") "en" else "ru"
    var document by remember(language) { mutableStateOf<PrivacyDocument?>(null) }
    var failed by remember(language) { mutableStateOf(false) }
    LaunchedEffect(context, language) {
        try {
            document = withContext(Dispatchers.IO) {
                context.assets.open("privacy/policy.json").bufferedReader(Charsets.UTF_8).use { reader ->
                    readPolicy(JSONObject(reader.readText()), language)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    BackHandler(onBack = onBack)
    val palette = MaterialTheme.colorScheme
    Scaffold(containerColor = palette.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedIconButton(onClick = onBack, modifier = Modifier.size(48.dp), shape = CircleShape,
                border = BorderStroke(1.dp, palette.outlineVariant)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
            Text(stringResource(R.string.privacy_policy_title), Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall)
        }
    }) { padding ->
        val policy = document
        if (policy == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (failed) Text(stringResource(R.string.privacy_policy_unavailable),
                    Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                else CircularProgressIndicator()
            }
        } else {
            val name = developerName.ifBlank { policy.publicName }
            val email = supportEmail.ifBlank { policy.supportEmail }
            fun replace(text: String) = text.replace("{{DEVELOPER_NAME}}", name).replace("{{SUPPORT_EMAIL}}", email)
            val updated = LocalDate.parse(policy.updatedAt)
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    item(key = "summary") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(R.string.privacy_policy_updated, updated, policy.version),
                                style = MaterialTheme.typography.labelMedium, color = palette.onSurfaceVariant)
                            Surface(shape = MaterialTheme.shapes.large, color = palette.surfaceContainerLow) {
                                Text(replace(policy.summary), Modifier.padding(20.dp),
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    items(policy.sections, key = { it.id }) { section ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(replace(section.title), style = MaterialTheme.typography.titleLarge)
                            section.paragraphs.forEach { paragraph ->
                                Text(replace(paragraph), style = MaterialTheme.typography.bodyLarge,
                                    color = palette.onSurface)
                            }
                        }
                    }
                    item(key = "contact") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            HorizontalDivider()
                            Text(stringResource(R.string.privacy_policy_contact), style = MaterialTheme.typography.labelLarge)
                            Text(email, style = MaterialTheme.typography.bodyLarge, color = palette.primary)
                        }
                    }
                }
            }
        }
    }
}

private data class PrivacySection(val id: String, val title: String, val paragraphs: List<String>)
private data class PrivacyDocument(val version: String, val updatedAt: String, val publicName: String,
    val supportEmail: String, val summary: String, val sections: List<PrivacySection>)

private fun readPolicy(root: JSONObject, language: String): PrivacyDocument {
    require(root.getInt("schema_version") == 1)
    val identity = root.getJSONObject("identity")
    val policy = root.getJSONObject("locales").getJSONObject(language)
    val rows = policy.getJSONArray("sections")
    val sections = List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val paragraphs = row.getJSONArray("paragraphs")
        PrivacySection(row.getString("id"), row.getString("title"), List(paragraphs.length()) { paragraphs.getString(it) })
    }
    require(sections.isNotEmpty() && sections.map { it.id }.distinct().size == sections.size)
    // Validate the date before rendering, so malformed public assets have a graceful fallback.
    val updatedAt = root.getString("updated_at").also { LocalDate.parse(it) }
    return PrivacyDocument(root.getString("policy_version"), updatedAt, identity.getString("public_name"),
        identity.getString("support_email"), policy.getString("summary"), sections)
}
