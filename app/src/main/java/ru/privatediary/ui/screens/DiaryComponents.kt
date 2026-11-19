package ru.privatediary.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import ru.privatediary.ui.theme.DiaryStyle

/** A small, local illustration: a notebook, a bookmark and the morning sun. */
@Composable
internal fun DiaryEmblem(modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = DiaryStyle.colors
    Canvas(modifier) {
        val unit = size.minDimension
        if (!compact) {
            drawCircle(colors.glow.copy(alpha = 0.18f), unit * 0.48f, Offset(unit * 0.5f, unit * 0.5f))
            drawCircle(colors.accent.copy(alpha = 0.13f), unit * 0.19f, Offset(unit * 0.79f, unit * 0.25f))
        }
        rotate(-9f, Offset(unit * 0.49f, unit * 0.53f)) {
            drawRoundRect(colors.cover, Offset(unit * 0.19f, unit * 0.15f), Size(unit * 0.59f, unit * 0.72f), CornerRadius(unit * 0.08f))
            drawLine(colors.onCover.copy(alpha = 0.28f), Offset(unit * 0.31f, unit * 0.19f), Offset(unit * 0.31f, unit * 0.83f), unit * 0.014f)
            drawCircle(colors.glow, unit * 0.12f, Offset(unit * 0.55f, unit * 0.41f))
            drawArc(colors.onCover.copy(alpha = 0.86f), 0f, 180f, false,
                Offset(unit * 0.4f, unit * 0.36f), Size(unit * 0.3f, unit * 0.2f), style = Stroke(unit * 0.017f))
            drawLine(colors.onCover.copy(alpha = 0.72f), Offset(unit * 0.43f, unit * 0.65f), Offset(unit * 0.66f, unit * 0.65f), unit * 0.017f)
            drawLine(colors.onCover.copy(alpha = 0.42f), Offset(unit * 0.43f, unit * 0.71f), Offset(unit * 0.6f, unit * 0.71f), unit * 0.017f)
            drawRoundRect(colors.accentContainer, Offset(unit * 0.64f, unit * 0.16f), Size(unit * 0.065f, unit * 0.16f), CornerRadius(unit * 0.01f))
        }
    }
}
