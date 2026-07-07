package com.example.cctvdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Wraps a YOLOv8(-nano)-style .tflite model exported with Ultralytics
 * (`yolo export format=tflite`). Output tensor shape is expected to be
 * [1, 4 + numClasses, numBoxes] (channels-first, no built-in NMS),
 * which is the default Ultralytics tflite export layout.
 *
 * Model file: place at app/src/main/assets/model.tflite
 * Label file: place at app/src/main/assets/labels.txt (one label per line)
 *
 * If your exported model already includes NMS (e.g. exported with
 * `nms=True`) you'll need to adjust decodeOutput() to match its output shape.
 */
class ObjectDetectorHelper(
    context: Context,
    modelPath: String = "model.tflite",
    labelPath: String = "labels.txt",
    private val confidenceThreshold: Float = 0.45f,
    private val iouThreshold: Float = 0.45f
) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    val labels: List<String>
    private val inputWidth: Int
    private val inputHeight: Int
    private val isQuantized: Boolean

    // Known average real-world widths (meters) per label, used for the
    // pinhole-camera distance estimate. Extend this as you add classes.
    private val knownWidthsMeters = mapOf(
        "security_camera" to 0.12f,
        "dome_camera" to 0.15f,
        "bullet_camera" to 0.20f
    )

    // Rough horizontal focal length in pixels for a typical phone's main
    // camera at the resolution CameraX gives the analyzer. This is NOT
    // calibrated per-device -- treat distance output as a ballpark, not
    // a measurement. Recalibrate using a known object at a known distance
    // if you need better accuracy.
    private val assumedFocalLengthPx = 1000f

    init {
        labels = try {
            FileUtil.loadLabels(context, labelPath)
        } catch (e: IOException) {
            listOf("security_camera")
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        val options = Interpreter.Options()

        val compatList = CompatibilityList()
        if (compatList.isDelegateSupportedOnThisDevice) {
            gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
            options.addDelegate(gpuDelegate)
        } else {
            options.setNumThreads(4)
        }

        interpreter = Interpreter(model, options)

        val inputTensor = interpreter!!.getInputTensor(0)
        val inputShape = inputTensor.shape() // [1, height, width, 3]
        inputHeight = inputShape[1]
        inputWidth = inputShape[2]
        isQuantized = inputTensor.dataType() == DataType.UINT8
    }

    /**
     * Runs detection on a single camera frame.
     *
     * @param bitmap frame from the camera analyzer, already rotated upright.
     * @param viewWidth width of the PreviewView being drawn to (for scaling boxes back).
     * @param viewHeight height of the PreviewView being drawn to.
     */
    fun detect(bitmap: Bitmap, viewWidth: Int, viewHeight: Int): List<Detection> {
        val interpreter = interpreter ?: return emptyList()

        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
            .build()
        var tensorImage = TensorImage(if (isQuantized) DataType.UINT8 else DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape() // [1, 4+numClasses, numBoxes]
        val numChannels = outputShape[1]
        val numBoxes = outputShape[2]
        val numClasses = numChannels - 4

        val output = Array(1) { Array(numChannels) { FloatArray(numBoxes) } }
        interpreter.run(tensorImage.buffer, output)

        val rawDetections = decodeOutput(output[0], numClasses, numBoxes, bitmap.width, bitmap.height)
        val finalDetections = nonMaxSuppression(rawDetections)

        // Scale from source-bitmap pixel coords -> PreviewView coords.
        val scaleX = viewWidth.toFloat() / bitmap.width
        val scaleY = viewHeight.toFloat() / bitmap.height

        return finalDetections.map { d ->
            val scaledBox = RectF(
                d.box.left * scaleX,
                d.box.top * scaleY,
                d.box.right * scaleX,
                d.box.bottom * scaleY
            )
            d.copy(box = scaledBox)
        }
    }

    private data class RawDet(val box: RectF, val classId: Int, val score: Float)

    private fun decodeOutput(
        output: Array<FloatArray>,
        numClasses: Int,
        numBoxes: Int,
        srcWidth: Int,
        srcHeight: Int
    ): List<Detection> {
        val results = mutableListOf<RawDet>()
        val scaleX = srcWidth.toFloat() / inputWidth
        val scaleY = srcHeight.toFloat() / inputHeight

        for (i in 0 until numBoxes) {
            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val score = output[4 + c][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestScore < confidenceThreshold) continue

            // Model outputs cx, cy, w, h normalized to input size.
            val cx = output[0][i] * inputWidth
            val cy = output[1][i] * inputHeight
            val w = output[2][i] * inputWidth
            val h = output[3][i] * inputHeight

            val left = (cx - w / 2f) * scaleX
            val top = (cy - h / 2f) * scaleY
            val right = (cx + w / 2f) * scaleX
            val bottom = (cy + h / 2f) * scaleY

            results.add(RawDet(RectF(left, top, right, bottom), bestClass, bestScore))
        }

        return results.map { r ->
            val label = labels.getOrElse(r.classId) { "object" }
            Detection(
                box = r.box,
                label = label,
                confidence = r.score,
                approxDistanceMeters = estimateDistance(label, r.box.width())
            )
        }
    }

    private fun estimateDistance(label: String, boxWidthPx: Float): Float? {
        if (boxWidthPx <= 0f) return null
        val realWidth = knownWidthsMeters[label] ?: return null
        return (realWidth * assumedFocalLengthPx) / boxWidthPx
    }

    /** Simple greedy per-class NMS. */
    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val byClass = detections.groupBy { it.label }
        val kept = mutableListOf<Detection>()

        for ((_, group) in byClass) {
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)
                sorted.removeAll { iou(it.box, best.box) > iouThreshold }
            }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val union = a.width() * a.height() + b.width() * b.height() - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
