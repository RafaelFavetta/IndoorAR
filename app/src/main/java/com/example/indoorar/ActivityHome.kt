package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class ActivityHome : AppCompatActivity() {

    // Launcher que recebe o resultado do scanner
    private val barcodeLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            // Scanner cancelado ou nenhum código detectado
            Snackbar.make(findViewById(R.id.main), "Nenhum código detectado", Snackbar.LENGTH_SHORT).show()
            println("Escaneamento cancelado ou sem resultado")
        } else {
            val qrContent = result.contents
            println("QR escaneado: $qrContent")
            abrirMapa(qrContent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val btnScanQR = findViewById<Button>(R.id.btnScanQR)
        btnScanQR.setOnClickListener {
            iniciarScanner()
        }
    }

    private fun iniciarScanner() {
        val options = ScanOptions().apply {
            setPrompt("Escaneie um QR Code")
            setBeepEnabled(true)
            setOrientationLocked(true)
            setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity::class.java)
            setDesiredBarcodeFormats(listOf("QR_CODE"))
        }
        barcodeLauncher.launch(options)
    }

    private fun abrirMapa(conteudoQR: String) {
        println("Abrindo mapa para: $conteudoQR")
        val intent = Intent(this, ActivityMap::class.java)
        intent.putExtra("qrData", conteudoQR)
        startActivity(intent)
    }


}