package com.example.indoorar

import android.content.Intent
import android.os.Bundle
import com.google.android.material.bottomnavigation.BottomNavigationView

class ActivityFavoritos : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_favoritos)

        // Wire bottom navigation (conta comum)
        findViewById<BottomNavigationView>(R.id.bottomNavComum)?.apply {
            // mark favoritos as selected
            selectedItemId = R.id.action_favoritos
            setOnItemSelectedListener { item ->
                if (this.selectedItemId == item.itemId) return@setOnItemSelectedListener true
                when (item.itemId) {
                    R.id.action_home -> { startActivity(Intent(this@ActivityFavoritos, ActivityHomeComum::class.java)); true }
                    R.id.action_scan -> { startActivity(Intent(this@ActivityFavoritos, ActivityScanQR::class.java)); true }
                    R.id.action_favoritos -> { true }
                    R.id.action_config -> { startActivity(Intent(this@ActivityFavoritos, ActivityPerfil::class.java)); true }
                    else -> false
                }
            }
        }
    }
}
