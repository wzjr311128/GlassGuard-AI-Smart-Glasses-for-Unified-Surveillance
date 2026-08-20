package com.example.parking.ui.scan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ScanFrameOverlay(
    modifier: Modifier = Modifier,
    isAnimating: Boolean,
    frameColor: Color = Color.White,
    cornerLength: Dp = 28.dp,
    strokeWidth: Dp = 2.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanLine")
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanProgress",
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cornerPx = cornerLength.toPx()
            val strokePx = strokeWidth.toPx()
            val width = size.width
            val height = size.height

            fun drawCorner(
                startX: Float,
                startY: Float,
                horizontalDirection: Float,
                verticalDirection: Float,
            ) {
                drawLine(
                    color = frameColor,
                    start = Offset(startX, startY),
                    end = Offset(startX + cornerPx * horizontalDirection, startY),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = frameColor,
                    start = Offset(startX, startY),
                    end = Offset(startX, startY + cornerPx * verticalDirection),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Square,
                )
            }

            drawCorner(0f, 0f, 1f, 1f)
            drawCorner(width, 0f, -1f, 1f)
            drawCorner(0f, height, 1f, -1f)
            drawCorner(width, height, -1f, -1f)

            if (isAnimating) {
                val linePadding = cornerPx * 0.6f
                val minY = linePadding
                val maxY = height - linePadding
                val lineY = minY + (maxY - minY) * scanProgress

                drawLine(
                    color = frameColor.copy(alpha = 0.9f),
                    start = Offset(linePadding, lineY),
                    end = Offset(width - linePadding, lineY),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
