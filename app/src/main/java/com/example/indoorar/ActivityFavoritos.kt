package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class ActivityFavoritos : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favoritos)

        // Aplicar insets no root: somente topo/laterais
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        // Bottom navigation (conta comum)
        findViewById<BottomNavigationView>(R.id.bottomNavComum)?.apply {
            // Evita padding inferior por insets para manter colada no fundo
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                insets
            }

            // mark favoritos as selected
            selectedItemId = R.id.action_favoritos
            setOnItemSelectedListener { item ->
                if (this.selectedItemId == item.itemId) return@setOnItemSelectedListener true
                when (item.itemId) {
                    R.id.action_home -> { startActivity(Intent(this@ActivityFavoritos, ActivityHomeComum::class.java)); true }
                    R.id.action_scan -> { startActivity(Intent(this@ActivityFavoritos, ActivityScanQR::class.java)); true }
                    R.id.action_favoritos -> { true }
                    R.id.action_config -> { startActivity(Intent(this@ActivityFavoritos, ActivityPerfilComum::class.java)); true }
                    else -> false
                }
            }
        }
    }
}
