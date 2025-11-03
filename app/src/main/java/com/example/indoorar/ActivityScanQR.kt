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
import androidx.core.graphics.toColorInt
import com.google.android.material.bottomnavigation.BottomNavigationView


class ActivityScanQR : BaseActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnEscanear: Button
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
        btnEscanear = findViewById(R.id.btnEscanear)

        // Wire bottom navigation (conta comum) — layout now contains `bottomNavComum`
        findViewById<BottomNavigationView>(R.id.bottomNavComum)?.apply {
            selectedItemId = R.id.action_scan
            setOnItemSelectedListener { item ->
                if (this.selectedItemId == item.itemId) return@setOnItemSelectedListener true
                when (item.itemId) {
                    R.id.action_home -> { startActivity(Intent(this@ActivityScanQR, ActivityHomeComum::class.java)); true }
                    R.id.action_scan -> { true }
                    R.id.action_favoritos -> { startActivity(Intent(this@ActivityScanQR, ActivityFavoritos::class.java)); true }
                    R.id.action_config -> { startActivity(Intent(this@ActivityScanQR, ActivityPerfilComum::class.java)); true }
                    else -> false
                }
            }
        }

        // Estado inicial: desabilitado e cinza
        btnEscanear.isEnabled = false
        btnEscanear.text = "ESCANEAR"
        btnEscanear.backgroundTintList = ColorStateList.valueOf("#BDBDBD".toColorInt())
        btnEscanear.setOnClickListener {
            val value = lastScanned ?: return@setOnClickListener
            if (navigating) return@setOnClickListener
            navigating = true
            btnEscanear.isEnabled = false
            handleResult(value)
        }

        ensureCameraPermissionAndStart()
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
                            btnEscanear.isEnabled = true
                            btnEscanear.text = "ESCANEAR"
                            btnEscanear.backgroundTintList = ColorStateList.valueOf("#32357A".toColorInt())
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

        // Tenta extrair um mapId válido do QR (id puro, URL com /mapas/{id} ou ?mapId=)
        val mapId = extractMapIdFromQr(value)
        if (mapId == null) {
            // Falha em interpretar o QR
            Toast.makeText(this, "QR inválido", Toast.LENGTH_SHORT).show()
            navigating = false
            btnEscanear.isEnabled = true
            return
        }

        stopCamera()

        if (returnResult) {
            val intent = Intent()
            intent.putExtra("QR_VALUE", mapId)
            setResult(RESULT_OK, intent)
            finish()
        } else {
            val mapIntent = Intent(this, ActivityNavHud::class.java).apply {
                putExtra("MAP_ID", mapId)
            }
            startActivity(mapIntent)
            finish()
        }
    }

    private fun extractMapIdFromQr(raw: String): String? {
        val s = raw.trim()
        // Caso 1: já é um id simples (sem barra)
        if (!s.contains('/')) return s

        // Caso 2: URL com parâmetro ?mapId=XYZ
        run {
            val idx = s.indexOf("mapId=")
            if (idx >= 0) {
                val sub = s.substring(idx + 6)
                val end = listOf('&', '#', '?', '/').map { c -> sub.indexOf(c).takeIf { it >= 0 } ?: sub.length }.min()
                val id = sub.substring(0, end)
                if (id.isNotBlank()) return id
            }
        }

        // Caso 3: caminho contendo /mapas/{id}
        run {
            val token = "/mapas/"
            val idx = s.indexOf(token)
            if (idx >= 0) {
                val sub = s.substring(idx + token.length)
                val end = sub.indexOf('/')
                val id = if (end >= 0) sub.substring(0, end) else sub
                if (id.isNotBlank()) return id
            }
        }

        // Última tentativa: pega o último segmento da URL
        run {
            val parts = s.split('/').filter { it.isNotBlank() }
            if (parts.isNotEmpty()) {
                val tail = parts.last()
                if (tail.isNotBlank()) return tail
            }
        }

        return null
    }
}