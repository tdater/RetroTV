package com.aiconsole.retrotv.ui

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CONFIRM_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A
)

/**
 * The basic TV control: focusable with the D-pad, grows and gets an accent border when focused,
 * OK clicks, holding OK long-clicks. A click only fires for a press that started here, so the
 * OK release that opened a screen can't click the first thing on it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvClickable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.05f,
    enabled: Boolean = true,
    background: Color = MaterialTheme.colorScheme.surface,
    focusedBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    onKey: ((KeyEvent) -> Boolean)? = null,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var downSeen by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onPreviewKeyEvent { e ->
                val native = e.nativeKeyEvent
                if (onKey != null && onKey(native)) return@onPreviewKeyEvent true
                if (!enabled || native.keyCode !in CONFIRM_KEYS) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (native.repeatCount == 0) {
                            downSeen = true
                            longFired = false
                        } else if (onLongClick != null && !longFired && downSeen) {
                            longFired = true
                            onLongClick()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (downSeen && !longFired) onClick()
                        downSeen = false
                        longFired = false
                        true
                    }
                    else -> false
                }
            }
            .background(if (focused) focusedBackground else background, shape)
            .border(if (focused) 3.dp else 1.dp, if (focused) accent else Color.White.copy(alpha = 0.06f), shape)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onLongClick = onLongClick,
                onClick = onClick
            ),
        contentAlignment = contentAlignment
    ) { content(focused) }
}

/** A labelled button with an optional symbol, sized for 10-foot viewing. */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    TvClickable(
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        background = if (selected) accent.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surface,
        focusedBackground = if (selected) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant,
        contentAlignment = Alignment.Center
    ) { focused ->
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Text(icon, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text,
                color = if (!enabled) TextDim.copy(alpha = 0.5f) else if (focused) Color.White else Color.White.copy(alpha = 0.9f),
                fontSize = 17.sp,
                fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A settings row: title, optional subtitle, and a value or switch on the right. */
@Composable
fun SettingRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null
) {
    TvClickable(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        focusedScale = 1.02f,
        onKey = { e ->
            if (e.action != KeyEvent.ACTION_DOWN) false
            else when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.let { it(); true } ?: false
                KeyEvent.KEYCODE_DPAD_RIGHT -> onRight?.let { it(); true } ?: false
                else -> false
            }
        }
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, color = Color.White, fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal)
                if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = TextDim)
            }
            Spacer(Modifier.width(16.dp))
            val arrows = onLeft != null && focused
            Text(
                if (arrows) "‹  $value  ›" else value,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** On/off setting row. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) =
    SettingRow(
        title = title,
        value = if (checked) "On" else "Off",
        onClick = { onChange(!checked) },
        modifier = modifier,
        subtitle = subtitle,
        onLeft = { onChange(!checked) },
        onRight = { onChange(!checked) }
    )

/** Choice among [options]: OK or right steps forward, left steps back. */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val i = options.indexOf(selected).coerceAtLeast(0)
    SettingRow(
        title = title,
        value = label(options[i]),
        onClick = { onSelect(options[(i + 1) % options.size]) },
        modifier = modifier,
        subtitle = subtitle,
        onLeft = { onSelect(options[(i - 1 + options.size) % options.size]) },
        onRight = { onSelect(options[(i + 1) % options.size]) }
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 18.dp, bottom = 8.dp, start = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )
}

@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    onDone: (() -> Unit)? = null,
    minLines: Int = 1
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Go else ImeAction.Default),
        keyboardActions = KeyboardActions(onGo = { onDone?.invoke() }, onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = TextDim.copy(alpha = 0.5f),
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = TextDim,
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
    )
}

/** A dimmed, centred panel drawn over the screen (menus and dialogs). */
@Composable
fun OverlayPanel(
    title: String,
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.7f,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .background(Color.Black.copy(alpha = 0.72f))
            .then(modifier),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(widthFraction)
                .background(Ink.copy(alpha = 0.97f), RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            content()
        }
    }
}

/** Small "pill" label, used for tags and status. */
@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.22f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) { Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, RoundedCornerShape(50)))
}
