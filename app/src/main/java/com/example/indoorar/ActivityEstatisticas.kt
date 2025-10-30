package com.example.indoorar

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import com.example.indoorar.R

class ActivityEstatisticas : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_estatisticas)
    }
}
