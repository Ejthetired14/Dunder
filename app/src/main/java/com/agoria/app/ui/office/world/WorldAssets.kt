package com.agoria.app.ui.office.world

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * office_sprites.png is 384 x 256: eight characters laid out as 4 across and 2 down. Each
 * character is a block of 3 columns (left step, stand, right step) by 4 rows (down, left,
 * right, up) of 32 px cells. This matches how AgentAvatar already crops the sheet.
 */
object SpriteSheet {
    const val CELL = 32

    /** Row inside a character block for each [Dir]. If left and right look swapped in game, swap 1 and 2. */
    private val rowForDir = intArrayOf(0, 1, 2, 3)

    /** Frame order while walking: stand, left step, stand, right step. */
    val WALK = intArrayOf(1, 0, 1, 2)

    fun srcOffset(avatar: Int, dir: Int, frame: Int): IntOffset {
        val idx = avatar.coerceIn(0, 7)
        val col = (idx % 4) * 3 + frame
        val row = (idx / 4) * 4 + rowForDir[dir.coerceIn(0, 3)]
        return IntOffset(col * CELL, row * CELL)
    }
}

/**
 * Decodes a drawable without density scaling. Plain Compose imageResource() decodes files in
 * res/drawable as if they were mdpi and scales them up for the screen, which can turn a 2k
 * image into a 4k bitmap (64 MB) on a phone like the Spark 20C. inScaled = false avoids that,
 * and inSampleSize caps the longest side at [maxDim].
 */
private fun decodeUnscaled(res: Resources, id: Int, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inScaled = false
    }
    BitmapFactory.decodeResource(res, id, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inScaled = false
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeResource(res, id, opts)
}

/** Loads a drawable off the main thread. Returns null until it is ready (or if decoding fails). */
@Composable
fun rememberResBitmap(@DrawableRes id: Int, maxDim: Int): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                decodeUnscaled(context.resources, id, maxDim)?.asImageBitmap()
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }
    return bitmap
}
