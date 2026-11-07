package ch.firn.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.firn.app.ui.theme.Ink
import ch.firn.app.ui.theme.InkType

/*
 * "Soft square" controls.
 *
 * One primary (white) action per screen; everything else tappable sits on a
 * dark surface; plain text on black is never tappable; selected = filled
 * #FAFAFA with #000 text. Labels are sentence case and never tracked.
 *
 * Controls draw their own pressed colours and pass `indication = null`: the
 * app-wide [PressFill] is a square fill and would spill past the corners.
 */

private val ButtonShape  = RoundedCornerShape(12.dp)
private val CardShape    = RoundedCornerShape(14.dp)
private val SegmentShape = RoundedCornerShape(9.dp)
private val SwitchShape  = RoundedCornerShape(8.dp)
private val KnobShape    = RoundedCornerShape(5.dp)

private val RegularPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp)
private val SmallPadding   = PaddingValues(horizontal = 14.dp, vertical = 9.dp)

/** Which surface a button sits on. */
enum class ButtonKind {
    /** `#FAFAFA` fill, black content. One per screen. */
    Primary,
    /** `#1A1A1A` fill, `.08` border, `#FAFAFA` content. */
    Secondary
}

private data class Surface(val fill: Color, val border: Color, val content: Color)

private fun buttonSurface(kind: ButtonKind, enabled: Boolean, pressed: Boolean): Surface = when {
    !enabled                     -> Surface(Ink.Control, Ink.ControlBorder, Ink.TertiaryIcon)
    kind == ButtonKind.Primary   -> Surface(if (pressed) Ink.PrimaryPressed else Ink.Text, Color.Transparent, Ink.Black)
    else                         -> Surface(if (pressed) Ink.ControlPressed else Ink.Control, Ink.ControlBorder, Ink.Text)
}

/**
 * Primary / secondary button: Inter 500 14sp, Phosphor Thin glyph at 19dp,
 * radius 12, padding 13/18. [small] is the in-row size: 12sp, glyph 15dp,
 * padding 9/14.
 *
 * With [fillWidth] and a [trailingIcon] the content spreads (`Browse models →`);
 * otherwise it is centred. [busy] keeps the look of [kind] but takes no taps —
 * `Reading` while a model loads.
 */
@Composable
fun SoftButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    small: Boolean = false,
    fillWidth: Boolean = false,
    padding: PaddingValues = if (small) SmallPadding else RegularPadding
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val surface = buttonSurface(kind, enabled, pressed)
    val gap = if (small) 7.dp else 9.dp
    val spread = fillWidth && trailingIcon != null

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(ButtonShape)
            .background(surface.fill)
            .border(1.dp, surface.border, ButtonShape)
            .clickable(
                interactionSource = source,
                indication        = null,
                enabled           = enabled && !busy,
                role              = Role.Button,
                onClick           = onClick
            )
            .semantics { if (busy) stateDescription = "In progress" }
            .padding(padding),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = if (spread) Arrangement.spacedBy(gap)
                                else Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    ) {
        if (icon != null) InkIcon(icon, if (small) 15.dp else 19.dp, tint = surface.content)
        Text(
            text     = label,
            style    = if (small) InkType.ControlSmall else InkType.Control,
            color    = surface.content,
            maxLines = 1,
            softWrap = false
        )
        if (trailingIcon != null) {
            if (spread) Spacer(Modifier.weight(1f))
            InkIcon(trailingIcon, 18.dp, tint = surface.content)
        }
    }
}

/**
 * A row's state where an action would be — `Installed`, `Too large`: the small
 * button's type and spacing at `.62`, with no surface, because it can't be tapped.
 */
@Composable
fun QuietLabel(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier              = modifier.padding(SmallPadding),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (icon != null) InkIcon(icon, 15.dp, tint = Ink.Placeholder)
        Text(label, style = InkType.ControlSmall, color = Ink.Placeholder, maxLines = 1, softWrap = false)
    }
}

/**
 * Chip: filters on the browse screen (padding 8/13, 12sp) and, with [large],
 * the suggestions on the chat empty state (10/14, 13sp). Selected chips are
 * filled `#FAFAFA` with black text.
 */
@Composable
fun SoftChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    large: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val surface = if (selected) {
        Surface(if (pressed) Ink.PrimaryPressed else Ink.Text, Color.Transparent, Ink.Black)
    } else {
        Surface(
            if (pressed) Ink.ControlPressed else Ink.Control,
            Ink.ControlBorder,
            if (large) Ink.Text else Ink.ControlText
        )
    }
    Box(
        modifier = modifier
            .clip(ButtonShape)
            .background(surface.fill)
            .border(1.dp, surface.border, ButtonShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { if (selected) stateDescription = "Selected" }
            .padding(
                if (large) PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                else PaddingValues(horizontal = 13.dp, vertical = 8.dp)
            )
    ) {
        Text(
            text     = label,
            style    = if (large) InkType.ChipLarge else InkType.Chip,
            color    = surface.content,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Segmented control (the Memory tabs): a `#141414` track, radius 12, padding 3;
 * equal cells, radius 9, the active one filled `#FAFAFA` with black text.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .background(Ink.SegmentTrack)
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { option ->
            val active = option == selected
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(SegmentShape)
                    .background(
                        when {
                            active  -> Ink.Text
                            pressed -> Ink.HoverFill
                            else    -> Color.Transparent
                        }
                    )
                    .selectable(
                        selected          = active,
                        interactionSource = source,
                        indication        = null,
                        role              = Role.Tab,
                        onClick           = { onSelect(option) }
                    )
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text     = label(option),
                    style    = InkType.ChipLarge,
                    color    = if (active) Ink.Black else Ink.SegmentText,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * Switch, 46×28dp, radius 8, with a 22dp knob (radius 5). On: `#FAFAFA` track,
 * black knob on the right. Off: `#2A2A2A` track, `#8C8C8C` knob on the left.
 *
 * Without [onCheckedChange] it only shows state — for a [ToggleCard], where
 * the whole card is the control.
 */
@Composable
fun SoftSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    val knobX by animateDpAsState(if (checked) SwitchTravel else 0.dp, tween(140), label = "switchKnob")
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(SwitchShape)
            .background(if (checked) Ink.Text else Ink.SwitchOffTrack)
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value             = checked,
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                        role              = Role.Switch,
                        onValueChange     = onCheckedChange
                    )
                } else Modifier
            )
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .offset(x = knobX)
                .size(22.dp)
                .shadow(1.dp, KnobShape)
                .background(if (checked) Ink.Black else Ink.SwitchOffKnob, KnobShape)
        )
    }
}

/** 46dp track − 2×3dp padding − 22dp knob. */
private val SwitchTravel: Dp = 18.dp

/**
 * Settings card with a switch (Memory · Learning): `#111`, `.06` border,
 * radius 14, padding 14/16. The whole card toggles.
 */
@Composable
fun ToggleCard(
    title: String,
    meta: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(if (pressed) Ink.CardPressed else Ink.Card)
            .border(1.dp, Ink.CardBorder, CardShape)
            .toggleable(
                value             = checked,
                interactionSource = source,
                indication        = null,
                role              = Role.Switch,
                onValueChange     = onCheckedChange
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = InkType.CardTitle, color = Ink.Text)
            Text(meta, style = InkType.SecondaryTight, color = Ink.Secondary)
        }
        SoftSwitch(checked)
    }
}
