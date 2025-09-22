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
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.appcompat.app.AlertDialog
import com.google.ar.core.ArCoreApk


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

        // Estado inicial: desabilitado e cinza
        btnResult.isEnabled = false
        btnResult.text = "ESCANEAR"
        btnResult.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#BDBDBD"))
        btnResult.setOnClickListener {
            val value = lastScanned ?: return@setOnClickListener
            handleResult(value)
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
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                cameraBound = true
            } catch (e: Exception) {
                Toast.makeText(this, "Erro iniciando câmera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun isArSupported(): Boolean {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        return availability == ArCoreApk.Availability.SUPPORTED_INSTALLED ||
                availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ||
                availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
    }

    private fun showNoArDialogAndGoHome(returnResult: Boolean) {
        if (returnResult) {
            // cancela para a Activity que chamou (ex.: ActivityMap) poder finalizar-se
            setResult(RESULT_CANCELED)
        }
        AlertDialog.Builder(this)
            .setTitle("Navegação indisponível")
            .setMessage("Seu dispositivo não suporta AR. Não é possível utilizar a navegação neste aparelho.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val intent = Intent(this, ActivityHomeComum::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                finish()
            }
            .show()
    }

    private fun handleResult(value: String) {
        val returnResult = intent.getBooleanExtra("RETURN_RESULT", false)

        if (!isArSupported()) {
            showNoArDialogAndGoHome(returnResult)
            return
        }

        // Se a Activity foi chamada para retornar resultado, devolve; senão, abre ActivityMap
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