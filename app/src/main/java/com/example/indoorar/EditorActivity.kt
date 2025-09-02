package com.example.indoorar

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)


        val mapEditor = findViewById<IndoorMapView>(R.id.mapEditor)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.tool_rectangle -> mapEditor.setTool(MapEditorView.Tool.RECTANGLE)
                R.id.tool_circle -> mapEditor.setTool(MapEditorView.Tool.CIRCLE)
                R.id.tool_select -> mapEditor.setTool(MapEditorView.Tool.SELECT)
            }
            true
        }
    }
}
