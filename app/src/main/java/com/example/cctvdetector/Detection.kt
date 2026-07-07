package com.example.cctvdetector

import android.graphics.RectF

/**
 * A single detected object.
 *
 * @param box bounding box in the coordinate space of the *preview view* (already scaled).
 * @param label class label, e.g. "security_camera".
 * @param confidence 0f..1f model confidence.
 * @param approxDistanceMeters rough distance estimate, or null if it can't be estimated.
 */
data class Detection(
    val box: RectF,
    val label: String,
    val confidence: Float,
    val approxDistanceMeters: Float?
)
