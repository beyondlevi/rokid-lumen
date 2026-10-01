package dev.lumen.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.lumen.companion.R
import dev.lumen.companion.speech.SpeechEngine
import dev.lumen.companion.speech.SpeechLanguage
import dev.lumen.companion.speech.SpeechPatience
import dev.lumen.companion.speech.SpeechProvider
import dev.lumen.companion.speech.SpeechSettings

/** The dictation engine's settings as the screens show them. */
data class DictationUiState(
    val engine: SpeechEngine = SpeechEngine.DEFAULT,
    val language: SpeechLanguage = SpeechLanguage.DEFAULT,
    val patience: SpeechPatience = SpeechPatience.DEFAULT,
    /** Providers with a saved API key. */
    val keys: Set<SpeechProvider> = emptySet(),
    val azureRegion: String? = null,
    val missing: SpeechSettings.Missing? = null,
)

private enum class DictationDialog { ENGINE, LANGUAGE, PATIENCE, KEY, REGION }

/** Engine (the Rokid Nexus options plus offline Vosk), language, patience and the API key. */
@Composable
internal fun DictationSection(state: CompanionUiState, actions: CompanionActions) {
    val speech = state.dictation
    var dialog by remember { mutableStateOf<DictationDialog?>(null) }
    SectionTitle(stringResource(R.string.settings_dictation))
    Group {
        ListRow(
            title = stringResource(R.string.speech_engine),
            subtitle = engineName(speech.engine),
            icon = LumenIcons.mic,
            trailing = {
                if (speech.missing == null) StatusPill(stringResource(R.string.speech_ready), Lumen.positive)
                else StatusPill(stringResource(R.string.speech_needs_setup), Lumen.warning)
            },
            onClick = { dialog = DictationDialog.ENGINE },
        )
        if (speech.engine.fixedLanguage) {
            ListRow(title = stringResource(R.string.speech_language), subtitle = stringResource(R.string.speech_language_fixed))
        } else {
            // A language Vosk has no model for falls back to English, and the row says so.
            val shown = if (speech.language in speech.engine.languages) speech.language else SpeechLanguage.EN
            ListRow(
                title = stringResource(R.string.speech_language),
                subtitle = when {
                    speech.engine.provider == SpeechProvider.VOSK -> stringResource(R.string.speech_language_note_vosk)
                    speech.language == SpeechLanguage.PT -> stringResource(R.string.speech_language_note_pt)
                    else -> null
                },
                value = languageLabel(shown),
                onClick = { dialog = DictationDialog.LANGUAGE },
            )
        }
        ListRow(
            title = stringResource(R.string.speech_patience),
            subtitle = stringResource(R.string.speech_patience_hint),
            value = stringResource(speech.patience.label),
            onClick = { dialog = DictationDialog.PATIENCE },
        )
        val provider = speech.engine.provider
        if (provider.needsKey) {
            val saved = provider in speech.keys
            ListRow(
                title = stringResource(R.string.speech_api_key, provider.displayName),
                subtitle = stringResource(if (saved) R.string.speech_key_saved else R.string.speech_key_missing),
                onClick = { dialog = DictationDialog.KEY },
            )
        }
        if (provider == SpeechProvider.AZURE) {
            ListRow(
                title = stringResource(R.string.speech_azure_region),
                subtitle = speech.azureRegion ?: stringResource(R.string.speech_key_missing),
                onClick = { dialog = DictationDialog.REGION },
            )
        }
        if (speech.missing == SpeechSettings.Missing.MICROPHONE) {
            ListRow(title = stringResource(R.string.speech_allow_mic), subtitle = stringResource(R.string.speech_allow_mic_hint), onClick = actions::allowMicrophone)
        }
        if (provider == SpeechProvider.VOSK) {
            ListRow(
                title = stringResource(R.string.speech_vosk_model),
                subtitle = state.modelProgress ?: stringResource(R.string.settings_voice_model_hint, state.modelLanguage),
                trailing = if (state.modelDownloaded) {
                    { StatusPill(stringResource(R.string.settings_downloaded), Lumen.positive) }
                } else {
                    null
                },
                value = if (state.modelDownloaded) null else stringResource(R.string.settings_download),
                onClick = if (state.modelDownloaded) null else actions::downloadModel,
            )
        }
    }

    when (dialog) {
        DictationDialog.ENGINE -> EngineDialog(speech.engine, onDismiss = { dialog = null }) { dialog = null; actions.setSpeechEngine(it) }
        DictationDialog.LANGUAGE -> OptionsDialog(
            stringResource(R.string.speech_language),
            speech.engine.languages.map { it to languageLabel(it) },
            speech.language,
            onDismiss = { dialog = null },
        ) { dialog = null; actions.setSpeechLanguage(it) }
        DictationDialog.PATIENCE -> OptionsDialog(
            stringResource(R.string.speech_patience),
            SpeechPatience.entries.map { it to stringResource(it.label) },
            speech.patience,
            onDismiss = { dialog = null },
        ) { dialog = null; actions.setSpeechPatience(it) }
        DictationDialog.KEY -> TextDialog(
            title = stringResource(R.string.speech_api_key, speech.engine.provider.displayName),
            label = stringResource(R.string.speech_key_hint, speech.engine.provider.displayName),
            initial = "",
            secret = true,
            canRemove = speech.engine.provider in speech.keys,
            onDismiss = { dialog = null },
            onRemove = { dialog = null; actions.removeSpeechKey(speech.engine.provider) },
        ) { dialog = null; actions.saveSpeechKey(speech.engine.provider, it) }
        DictationDialog.REGION -> TextDialog(
            title = stringResource(R.string.speech_azure_region),
            label = stringResource(R.string.speech_azure_region_hint),
            initial = speech.azureRegion.orEmpty(),
            secret = false,
            canRemove = false,
            onDismiss = { dialog = null },
            onRemove = {},
        ) { dialog = null; actions.saveAzureRegion(it) }
        null -> Unit
    }
}

internal fun engineName(engine: SpeechEngine): String =
    if (engine.provider == SpeechProvider.VOSK || engine.provider == SpeechProvider.ANDROID) engine.displayName
    else "${engine.provider.displayName} · ${engine.displayName}"

@Composable
private fun languageLabel(language: SpeechLanguage): String =
    if (language == SpeechLanguage.AUTO) stringResource(R.string.speech_language_auto) else language.nativeName

private val fieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Lumen.accent,
        unfocusedBorderColor = Lumen.border,
        focusedTextColor = Lumen.textPrimary,
        unfocusedTextColor = Lumen.textPrimary,
        cursorColor = Lumen.accent,
        focusedLabelColor = Lumen.accent,
        unfocusedLabelColor = Lumen.textPlaceholder,
    )

/** Every engine, by provider, with what it's best at. */
@Composable
private fun EngineDialog(current: SpeechEngine, onDismiss: () -> Unit, onChoose: (SpeechEngine) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = { Text(stringResource(R.string.speech_engine)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
                SpeechProvider.entries.forEach { provider ->
                    val engines = SpeechEngine.entries.filter { it.provider == provider }
                    Text(provider.displayName.uppercase(), style = MaterialTheme.typography.labelMedium, color = Lumen.textPlaceholder, modifier = Modifier.padding(top = Lumen.spacingSmall))
                    engines.forEach { engine ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton) { onChoose(engine) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall),
                        ) {
                            RadioButton(
                                selected = engine == current,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Lumen.accent, unselectedColor = Lumen.textPlaceholder),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(engine.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(engine.description), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
                                Text(stringResource(engine.badges), style = MaterialTheme.typography.labelSmall, color = Lumen.textPlaceholder)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}

@Composable
private fun <T> OptionsDialog(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onChoose: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { onChoose(value) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Lumen.spacingSmall),
                    ) {
                        RadioButton(
                            selected = value == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Lumen.accent, unselectedColor = Lumen.textPlaceholder),
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}

@Composable
private fun TextDialog(
    title: String,
    label: String,
    initial: String,
    secret: Boolean,
    canRemove: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lumen.elevation1,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value, { value = it },
                label = { Text(label) },
                singleLine = true,
                colors = fieldColors,
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                // An API key: no suggestions, no learning by the keyboard.
                keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
            )
        },
        confirmButton = {
            Row {
                if (canRemove) TextButton(onClick = onRemove) { Text(stringResource(R.string.speech_remove), color = Lumen.negative) }
                TextButton(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.apps_config_save), color = Lumen.accent) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Lumen.textPrimary) } },
    )
}
