package com.aipaca.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aipaca.app.ui.theme.Ink
import com.aipaca.app.ui.theme.InkType
import com.aipaca.app.ui.theme.Ph

// ---- Dialog ------------------------------------------------------------------

/**
 * Square sheet-coloured dialog: tracked title with a close glyph, free content,
 * and a right-aligned row of [TextAction]s.
 */
@Composable
fun InkDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .background(Ink.Sheet)
                .border(1.dp, Ink.Border)
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tracked(title, InkType.SheetTitle, modifier = Modifier.weight(1f).padding(top = 12.dp))
                IconAction(Ph.X, "Close", onDismiss, size = 16.dp, tint = Ink.white(.5f))
            }
            Column(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (subtitle != null) Tracked(subtitle, InkType.Label, color = Ink.Meta)
                content()
            }
            Row(
                modifier              = Modifier.fillMaxWidth().padding(end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.End),
                verticalAlignment     = Alignment.CenterVertically,
                content               = actions
            )
        }
    }
}

// ---- Text field ----------------------------------------------------------------

/**
 * Instrument text field. [boxed] draws a 1dp `.2` frame (multi-line editors);
 * otherwise a `.2` underline (search, single values). Placeholders are chrome:
 * tracked uppercase at `.62`.
 */
@Composable
fun InkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    /** Placeholder is an example value (a URL, a key prefix): shown as typed, not as chrome. */
    literalPlaceholder: Boolean = false,
    boxed: Boolean = false,
    singleLine: Boolean = !boxed,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    textStyle: TextStyle = InkType.Input,
    leadingIcon: ImageVector? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    BasicTextField(
        value                = value,
        onValueChange        = onValueChange,
        enabled              = enabled,
        singleLine           = singleLine,
        minLines             = minLines,
        maxLines             = maxLines,
        textStyle            = textStyle.copy(color = if (enabled) Ink.Text else Ink.Meta),
        cursorBrush          = SolidColor(Ink.White),
        keyboardOptions      = keyboardOptions,
        keyboardActions      = keyboardActions,
        visualTransformation = visualTransformation,
        modifier             = modifier.fillMaxWidth(),
        decorationBox        = { inner ->
            val frame = if (boxed) {
                Modifier.border(1.dp, Ink.Border).padding(horizontal = 14.dp, vertical = 12.dp)
            } else {
                Modifier.hairlineBottom(Ink.Border).padding(bottom = 11.dp)
            }
            Row(
                modifier              = frame,
                verticalAlignment     = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (leadingIcon != null) InkIcon(leadingIcon, 16.dp, tint = Ink.white(.6f))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        if (literalPlaceholder) {
                            Text(placeholder, style = textStyle, color = Ink.Placeholder, maxLines = 1)
                        } else {
                            Tracked(
                                placeholder,
                                InkType.Placeholder.copy(fontSize = textStyle.fontSize),
                                color    = Ink.Placeholder,
                                maxLines = 1
                            )
                        }
                    }
                    inner()
                }
            }
        }
    )
}

/** Small tracked label above a field in a dialog. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Tracked(text, InkType.Label, color = Ink.Meta, modifier = modifier)
}

// ---- Menu ----------------------------------------------------------------------

@Composable
fun InkMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded         = expanded,
        onDismissRequest = onDismissRequest,
        modifier         = modifier,
        offset           = offset,
        shape            = RoundedCornerShape(0.dp),
        containerColor   = Ink.Sheet,
        tonalElevation   = 0.dp,
        shadowElevation  = 0.dp,
        border           = BorderStroke(1.dp, Ink.Border),
        content          = content
    )
}

/**
 * Menu row: glyph + tracked label, `#FFF` when [active], `.78` otherwise.
 * [trailing] shows state such as `ON` / `OFF`.
 */
@Composable
fun InkMenuItem(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    active: Boolean = false,
    trailing: String? = null
) {
    val tint = if (active) Ink.White else Ink.Meta
    Row(
        modifier = Modifier
            .widthIn(min = 200.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 14.dp)),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) InkIcon(icon, 16.dp, tint = tint)
        Tracked(label, InkType.Button, color = tint, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.widthIn(min = 12.dp))
            Tracked(trailing, InkType.Label, color = if (active) Ink.White else Ink.Placeholder, maxLines = 1)
        }
    }
}

// ---- Snackbar ------------------------------------------------------------------

@Composable
fun InkSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .background(Ink.Sheet)
                .border(1.dp, Ink.Border)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(data.visuals.message, style = InkType.Secondary, color = Ink.Chip)
        }
    }
}
