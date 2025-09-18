package com.example.indoorar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.firebase.auth.FirebaseAuth
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import androidx.core.net.toUri
import com.example.indoorar.BaseActivity


class ActivityScanQR : BaseActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnResult: Button
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var lastScanned: String? = null
    private var cameraBound = false

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

        btnResult.isEnabled = false
        btnResult.setOnClickListener {
            lastScanned?.let { value ->
                // Se for URL, abre; senão, mostra Toast
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    startActivity(Intent(Intent.ACTION_VIEW, value.toUri()))
                } else {
                    Toast.makeText(this, "QR Lido: $value", Toast.LENGTH_LONG).show()
                }
            }
        }

        ensureCameraPermissionAndStart()
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
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val reader = MultiFormatReader().apply {
                setHints(mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to arrayListOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                ))
            }

            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    val crop = imageProxy.cropRect
                    val source = ImageUtils.getLuminanceSourceFromImage(mediaImage, crop)
                    val bitmap = BinaryBitmap(HybridBinarizer(source))
                    try {
                        val result = reader.decode(bitmap)
                        if (result.text != lastScanned) {
                            lastScanned = result.text
                            btnResult.isEnabled = true
                            btnResult.text = "Usar: ${result.text.take(24)}" + if (result.text.length > 24) "..." else ""
                            btnResult.setOnClickListener { handleResult(result.text) }
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
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                cameraBound = true
            } catch (e: Exception) {
                Toast.makeText(this, "Erro iniciando câmera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleResult(value: String) {
        val intent = Intent()
        intent.putExtra("QR_VALUE", value)
        setResult(RESULT_OK, intent)
        finish()
    }
}