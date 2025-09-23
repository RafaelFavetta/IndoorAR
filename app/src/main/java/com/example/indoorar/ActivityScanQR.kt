package com.example.indoorar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import android.content.res.ColorStateList
import android.graphics.Color
import com.google.ar.core.ArCoreApk


class ActivityScanQR : BaseActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnResult: Button
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var lastScanned: String? = null
    private var cameraBound = false

    // Hold references to release camera cleanly
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Prevent double navigation
    private var navigating = false

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Permissão de câmera é necessária.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanqr)

        previewView = findViewById(R.id.previewView)
        btnResult = findViewById(R.id.btnResult)

        // Estado inicial: desabilitado e cinza
        btnResult.isEnabled = false
        btnResult.text = "ESCANEAR"
        btnResult.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#BDBDBD"))
        btnResult.setOnClickListener {
            val value = lastScanned ?: return@setOnClickListener
            if (navigating) return@setOnClickListener
            navigating = true
            btnResult.isEnabled = false
            handleResult(value)
        }

        // Block scanning entirely only on devices without ARCore support
        if (!checkArCoreSupport()) return

        ensureCameraPermissionAndStart()
    }

    // ARCore availability
    private fun checkArCoreSupport(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isUnsupported) {
            Toast.makeText(this, "Seu dispositivo não contém suporte ao ARCore.", Toast.LENGTH_LONG).show()
            finish()
            return false
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        // Ensure camera is released as we leave
        stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
    }

    private fun ensureCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(this, "Precisamos da câmera para ler o QR.", Toast.LENGTH_SHORT).show()
                requestPermission.launch(Manifest.permission.CAMERA)
            }

            else -> requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        if (cameraBound) return

        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            imageAnalysis = analysis

            val reader = MultiFormatReader().apply {
                setHints(mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                ))
            }

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val crop = imageProxy.cropRect
                    val source = ImageUtils.getLuminanceSourceFromImage(mediaImage, crop)
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    try {
                        val result = reader.decode(bitmap)
                        if (result.text != lastScanned) {
                            lastScanned = result.text
                            // Update UI: enable and turn blue with ESCANEAR
                            btnResult.isEnabled = true
                            btnResult.text = "ESCANEAR"
                            btnResult.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#32357A"))
                        }
                    } catch (_: NotFoundException) {
                        // nada encontrado no frame
                    } catch (_: Exception) {
                        // qualquer outra exceção do ZXing
                    } finally {
                        reader.reset() // evita estado sujo entre frames
                        imageProxy.close()
                    }
                } else {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, analysis)
                cameraBound = true
            } catch (e: Exception) {
                Toast.makeText(this, "Erro iniciando câmera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try { imageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
        try {
            if (cameraProvider == null) {
                // Try to resolve from future if possible
                cameraProvider = try { cameraProviderFuture.get() } catch (_: Exception) { null }
            }
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        cameraBound = false
    }

    private fun handleResult(value: String) {
        val returnResult = intent.getBooleanExtra("RETURN_RESULT", false)

        // Release camera promptly before leaving to avoid camera-in-use crash
        stopCamera()

        // Proceed without blocking on ARCore pre-check; ARActivity/flow should handle ARCore install if needed
        if (returnResult) {
            val intent = Intent()
            intent.putExtra("QR_VALUE", value)
            setResult(RESULT_OK, intent)
            finish()
        } else {
            val mapIntent = Intent(this, ActivityMap::class.java).apply {
                putExtra("MAP_ID", value)
            }
            startActivity(mapIntent)
            finish()
        }
    }
}