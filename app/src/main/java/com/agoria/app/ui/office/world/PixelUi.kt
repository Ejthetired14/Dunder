package com.agoria.app.ui.office.world

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

/** Palette pulled from the office map: navy walls, teal floor, warm wood, paper cream. */
object Pal {
    val ink = Color(0xFF14161F)
    val navy = Color(0xFF232842)
    val teal = Color(0xFF2E6B69)
    val cream = Color(0xFFF3E5C5)
    val tan = Color(0xFFD9B98B)
    val wood = Color(0xFFB5703B)
    val amber = Color(0xFFFFC83D)
    val red = Color(0xFFD9534F)
}

/** Labels use the system monospace for now. Drop a pixel font (e.g. Silkscreen) into res/font and swap it in here. */
val PixelLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 12.sp,
    letterSpacing = 0.6.sp,
    color = Pal.ink,
)

/** Body copy stays a normal readable sans. Diary and chat text must never be pixel font. */
val PixelBody = TextStyle(
    fontSize = 13.sp,
    lineHeight = 18.sp,
    color = Pal.ink,
)

/** Square-cornered panel with a hard 2 dp border and an offset drop shadow. No blur, no rounding. */
@Composable
fun PixelPanel(
    modifier: Modifier = Modifier,
    fill: Color = Pal.cream,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .drawBehind {
                val o = 3.dp.toPx()
                drawRect(Color(0x66000000), topLeft = Offset(o, o), size = size)
            }
            .background(fill)
            .border(2.dp, Pal.ink),
    ) {
        content()
    }
}

/** `small = true` gives a 36 dp tall, 11 sp variant for dense cards. Default look is unchanged. */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Pal.amber,
    small: Boolean = false,
) {
    Box(
        modifier = modifier
            .heightIn(min = if (small) 36.dp else 44.dp)
            .background(fill)
            .border(2.dp, Pal.ink)
            .clickable(onClick = {
                OfficeSound.play(OfficeSound.Fx.CLICK)
                onClick()
            })
            .padding(horizontal = if (small) 10.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = if (small) PixelLabel.copy(fontSize = 11.sp) else PixelLabel,
        )
    }
}

@Composable
fun PixelIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PixelPanel(modifier = modifier.size(44.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = {
                    OfficeSound.play(OfficeSound.Fx.CLICK)
                    onClick()
                }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = Pal.ink,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Square flat text box: cream fill, 2 dp ink border, ink cursor, 14 sp body text.
 * Replaces OutlinedTextField (task T-FIELDS). `singleLine = false` makes a taller (88 dp min) multi-line box.
 *
 * All parameters after `singleLine` are optional and additive, so existing calls keep compiling:
 * - `password = true` masks the text and asks the keyboard for a password field (API keys).
 * - `hint` is small helper text under the box (replaces OutlinedTextField `supportingText`).
 * - `labelColor` / `hintColor` default to amber / tan for dark backgrounds (office HUD);
 *   pass darker colours when the field sits on a cream card.
 */
@Composable
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    password: Boolean = false,
    hint: String? = null,
    labelColor: Color = Pal.amber,
    hintColor: Color = Pal.tan,
    onDone: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = PixelLabel.copy(color = labelColor, fontSize = 11.sp),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = PixelBody.copy(fontSize = 14.sp),
            cursorBrush = SolidColor(Pal.ink),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = when {
                password -> KeyboardOptions(keyboardType = KeyboardType.Password)
                onDone != null -> KeyboardOptions(imeAction = ImeAction.Done)
                else -> KeyboardOptions.Default
            },
            keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 44.dp else 88.dp)
                .background(Pal.cream)
                .border(2.dp, Pal.ink)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = PixelBody.copy(fontSize = 11.sp, lineHeight = 15.sp, color = hintColor),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Square flat switch (56 x 32 dp): teal track when on, navy when off, square cream thumb.
 * Replaces the Material Switch (task T-FIELDS). `onChange` is the last parameter so trailing-lambda calls work.
 */
@Composable
fun PixelSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Box(
        modifier = modifier
            .width(56.dp)
            .height(32.dp)
            .background(if (checked) Pal.teal else Pal.navy)
            .border(2.dp, Pal.ink)
            .clickable(onClick = {
                OfficeSound.play(OfficeSound.Fx.CLICK)
                onChange(!checked)
            })
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .background(Pal.cream)
                .border(2.dp, Pal.ink),
        )
    }
}


/**
 * Shared square dialog (task T-PIXEL-WIDGETS): cream [PixelPanel] with a hard shadow and 2 dp ink border,
 * ink content colour (so default Text is readable on cream), 16 dp padding, 10 dp between children.
 * `title` is drawn as an uppercase [PixelLabel]. Replaces the private ResetDialog (Settings) and the
 * hand-built hire dialog (Home). Put fields and buttons in `content`.
 */
@Composable
fun PixelDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(modifier = modifier.fillMaxWidth()) {
            CompositionLocalProvider(LocalContentColor provides Pal.ink) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (title != null) {
                        Text(title.uppercase(), style = PixelLabel)
                    }
                    content()
                }
            }
        }
    }
}

/** Flat square progress bar: navy track, amber fill, 2 dp ink border. `fraction` is 0f..1f. */
@Composable
fun PixelProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    fill: Color = Pal.amber,
) {
    Box(
        modifier = modifier
            .height(16.dp)
            .background(Pal.navy)
            .border(2.dp, Pal.ink),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(fill),
        )
    }
}

/**
 * Square slider (task T-PIXEL-WIDGETS): navy track with 2 dp ink border, amber fill, 16 x 28 dp cream thumb.
 * Same contract as the Material Slider for the parameters we use: `valueRange`, and `steps` = number of
 * discrete values strictly between the ends (so there are `steps + 1` intervals). Tap or drag anywhere.
 * 32 dp tall, fills the width it is given.
 */
@Composable
fun PixelSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    fill: Color = Pal.amber,
) {
    val start = valueRange.start
    val span = valueRange.endInclusive - start
    val fraction = if (span <= 0f) 0f else ((value - start) / span).coerceIn(0f, 1f)
    val thumbWidth = 16.dp
    val thumbPx = with(LocalDensity.current) { thumbWidth.roundToPx() }
    var widthPx by remember { mutableStateOf(0) }
    val latestOnChange by rememberUpdatedState(onValueChange)

    fun valueAt(x: Float): Float {
        val usable = (widthPx - thumbPx).coerceAtLeast(1)
        var f = ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
        if (steps > 0) {
            val n = steps + 1
            f = (f * n).roundToInt() / n.toFloat()
        }
        return start + f * span
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(start, span, steps, thumbPx) {
                detectTapGestures { offset -> latestOnChange(valueAt(offset.x)) }
            }
            .pointerInput(start, span, steps, thumbPx) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> latestOnChange(valueAt(offset.x)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        latestOnChange(valueAt(change.position.x))
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(Pal.navy)
                .border(2.dp, Pal.ink),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(fill),
            )
        }
        Box(
            Modifier
                .offset { IntOffset(((widthPx - thumbPx) * fraction).roundToInt().coerceAtLeast(0), 0) }
                .size(thumbWidth, 28.dp)
                .background(Pal.cream)
                .border(2.dp, Pal.ink),
        )
    }
}
