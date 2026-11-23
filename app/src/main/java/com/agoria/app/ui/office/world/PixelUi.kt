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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Pal.amber,
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(fill)
            .border(2.dp, Pal.ink)
            .clickable(onClick = {
                OfficeSound.play(OfficeSound.Fx.CLICK)
                onClick()
            })
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = PixelLabel)
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

/** Square flat text box: cream fill, 2 dp ink border, ink cursor, 14 sp body text. Replaces OutlinedTextField (task T-FIELDS). */
@Composable
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = PixelLabel.copy(color = Pal.amber, fontSize = 11.sp),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = PixelBody.copy(fontSize = 14.sp),
            cursorBrush = SolidColor(Pal.ink),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(Pal.cream)
                .border(2.dp, Pal.ink)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}
}
