package com.example.cctvdetector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.cctvdetector.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var detector: ObjectDetectorHelper? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Detection is heavier than the camera frame rate needs; this flag
    // makes sure we only run one inference at a time and skip frames
    // while busy, instead of queuing them up.
    private val isProcessing = AtomicBoolean(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        try {
            detector = ObjectDetectorHelper(this)
            binding.statusText.text = "Point your camera around to scan for cameras"
        } catch (e: Exception) {
            // Most likely cause: model.tflite is missing from assets/.
            binding.statusText.text =
                "Model not loaded: place a trained model.tflite in app/src/main/assets/ (see README)."
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val currentDetector = detector
                if (currentDetector == null || !isProcessing.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                try {
                    val bitmap = imageProxy.toUprightBitmap()
                    val results = currentDetector.detect(
                        bitmap,
                        binding.overlayView.width,
                        binding.overlayView.height
                    )
                    runOnUiThread {
                        binding.overlayView.setDetections(results)
                        binding.statusText.text = if (results.isEmpty()) {
                            "Scanning..."
                        } else {
                            "Detected ${results.size} camera(s)"
                        }
                    }
                } catch (e: Exception) {
                    // Swallow per-frame errors so one bad frame doesn't crash the analyzer loop.
                } finally {
                    isProcessing.set(false)
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
    }
}
