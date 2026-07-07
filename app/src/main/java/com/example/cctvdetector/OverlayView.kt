package com.example.cctvdetector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#00E5A0")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.parseColor("#CC00E5A0")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 38f
        isAntiAlias = true
        isFakeBoldText = true
    }

    fun setDetections(newDetections: List<Detection>) {
        detections = newDetections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (d in detections) {
            canvas.drawRect(d.box, boxPaint)

            val confidencePct = (d.confidence * 100).roundToInt()
            val distanceStr = d.approxDistanceMeters?.let { " ~%.1fm".format(it) } ?: ""
            val label = "${prettyLabel(d.label)} $confidencePct%$distanceStr"

            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize

            canvas.drawRect(
                d.box.left,
                d.box.top - textHeight - 12f,
                d.box.left + textWidth + 16f,
                d.box.top,
                textBackgroundPaint
            )
            canvas.drawText(label, d.box.left + 8f, d.box.top - 10f, textPaint)
        }
    }

    private fun prettyLabel(raw: String): String = raw.replace("_", " ").replaceFirstChar { it.uppercase() }
}
