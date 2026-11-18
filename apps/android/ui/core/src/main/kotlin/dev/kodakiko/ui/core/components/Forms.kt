package dev.kodakiko.ui.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.kodakiko.ui.core.format.BannerMessage
import dev.kodakiko.ui.core.format.text
import dev.kodakiko.ui.core.icons.KodakikoIcons
import dev.kodakiko.ui.core.util.optionalTestTag
import dev.kodakiko.ui.core.util.runOnEnterKeyPressed

enum class BannerKind { ERROR, WARNING, INFO }

enum class FormButtonStyle { PRIMARY, SECONDARY, TEXT }

@Composable
fun Banner(
    text: String,
    kind: BannerKind,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        BannerKind.ERROR -> colors.errorContainer to colors.onErrorContainer
        BannerKind.WARNING -> colors.tertiaryContainer to colors.onTertiaryContainer
        BannerKind.INFO -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .optionalTestTag(testTag),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(ComponentConstants.BANNER_PADDING),
            horizontalArrangement = Arrangement.spacedBy(ComponentConstants.BANNER_ICON_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = KodakikoIcons.Info, contentDescription = null)
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ErrorBanner(
    message: BannerMessage,
    modifier: Modifier = Modifier,
) {
    Banner(
        text = message.text(LocalContext.current.resources),
        kind = BannerKind.ERROR,
        modifier = modifier,
        testTag = message.testTag,
    )
}

@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    testTag: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    singleLine: Boolean = true,
    capitalize: Boolean = false,
    errorText: String? = null,
    errorTestTag: String? = null,
    enabled: Boolean = true,
    submitOnEnter: Boolean = false,
) {
    val enterKey = if (submitOnEnter && onImeAction != null) Modifier.runOnEnterKeyPressed(onImeAction) else Modifier
    val actions = onImeAction?.let { action ->
        KeyboardActions(
            onDone = { action() },
            onGo = { action() },
            onNext = { action() },
            onSearch = { action() },
            onSend = { action() },
        )
    } ?: KeyboardActions.Default
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().then(enterKey).testTag(testTag),
            enabled = enabled,
            label = label?.let { { Text(text = it) } },
            isError = errorText != null,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = actions,
            singleLine = singleLine,
        )
        if (errorText != null) {
            FormHint(text = errorText, testTag = errorTestTag, modifier = Modifier.padding(top = ComponentConstants.FIELD_ERROR_SPACING))
        }
    }
}

@Composable
fun FormButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    style: FormButtonStyle = FormButtonStyle.PRIMARY,
    enabled: Boolean = true,
    busy: Boolean = false,
    contentColor: Color? = null,
) {
    val buttonModifier = modifier.fillMaxWidth().testTag(testTag)
    val label: @Composable () -> Unit = {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(ComponentConstants.BUTTON_PROGRESS_SIZE),
                color = LocalContentColor.current,
                strokeWidth = ComponentConstants.BUTTON_PROGRESS_STROKE,
            )
            Spacer(Modifier.width(ComponentConstants.BUTTON_PROGRESS_SPACING))
        }
        Text(text = text, color = contentColor ?: Color.Unspecified)
    }
    when (style) {
        FormButtonStyle.PRIMARY -> Button(onClick = onClick, modifier = buttonModifier, enabled = enabled && !busy) { label() }
        FormButtonStyle.SECONDARY -> OutlinedButton(onClick = onClick, modifier = buttonModifier, enabled = enabled && !busy) { label() }
        FormButtonStyle.TEXT -> TextButton(onClick = onClick, modifier = buttonModifier, enabled = enabled && !busy) { label() }
    }
}

@Composable
fun FormSwitch(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .testTag(testTag)
            .padding(vertical = ComponentConstants.BANNER_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun FormHint(
    text: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    error: Boolean = true,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().optionalTestTag(testTag),
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
