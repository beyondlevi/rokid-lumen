package dev.lumen.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.lumen.companion.R
import dev.lumen.protocol.KeyboardCommand
import dev.lumen.protocol.KeyboardField
import kotlinx.coroutines.delay

/**
 * Types into the text field focused on the glasses, in any app there (Lumen's keyboard is their
 * input method): every change goes out live as the field's whole text, and the keyboard's action
 * is the field's Enter there (its search, its send). While this page is in front the glasses know
 * the keyboard is open, and send the field's text; it closes when the page goes or the companion
 * leaves the screen. The glasses' panel opens it with a notification ([dev.lumen.companion.KeyboardLink]).
 */
@Composable
internal fun KeyboardPage(field: KeyboardField, linked: Boolean, actions: CompanionActions, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.keyboard_title), onBack)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (true) {
                    actions.keyboardOpen()
                    delay(KeyboardCommand.HEARTBEAT_MS)
                }
            } finally {
                actions.keyboardClose()
            }
        }
    }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    // A new field (or the keyboard just opened) starts from its value, and so does the page's own
    // change after an Enter (a sent message clears the box); typing here never waits on it.
    LaunchedEffect(field) {
        if (field.focused && field.reason != KeyboardField.BLUR && field.value != value.text) {
            value = TextFieldValue(field.value, TextRange(field.value.length))
        }
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Text(
        when {
            !linked -> stringResource(R.string.keyboard_no_link)
            !field.focused -> stringResource(R.string.keyboard_no_field)
            else -> stringResource(R.string.keyboard_typing_into, fieldName(field), field.app)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Lumen.textSecondary,
    )
    val password = field.type == "password"
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            val changed = next.text != value.text
            value = next
            if (changed) actions.keyboardText(next.text)
        },
        enabled = linked,
        singleLine = !field.multiline,
        minLines = if (field.multiline) 3 else 1,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (field.type) {
                "password" -> KeyboardType.Password
                "email" -> KeyboardType.Email
                "url" -> KeyboardType.Uri
                "tel" -> KeyboardType.Phone
                "number" -> KeyboardType.Number
                else -> KeyboardType.Text
            },
            imeAction = if (field.multiline) ImeAction.Default else ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { actions.keyboardEnter() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Lumen.accent,
            unfocusedBorderColor = Lumen.border,
            focusedTextColor = Lumen.textPrimary,
            unfocusedTextColor = Lumen.textPrimary,
            cursorColor = Lumen.accent,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(focus),
    )
    Column(verticalArrangement = Arrangement.spacedBy(Lumen.spacingSmall)) {
        PillButton(stringResource(R.string.keyboard_enter), primary = true, enabled = linked && field.focused, modifier = Modifier.fillMaxWidth()) {
            actions.keyboardEnter()
        }
        Text(stringResource(R.string.keyboard_hint), style = MaterialTheme.typography.bodySmall, color = Lumen.textSecondary)
    }
}

/** The field's label, or what kind of field it is. */
@Composable
internal fun fieldName(field: KeyboardField): String = field.label.ifEmpty {
    stringResource(if (field.type == "password") R.string.keyboard_field_password else R.string.keyboard_field_text)
}
