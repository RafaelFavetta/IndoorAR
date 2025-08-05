package com.example.indoorar

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.ar.sceneform.ux.ArFragment

class ActivityMap : AppCompatActivity() {

    private lateinit var arFragment: ArFragment
    private lateinit var loadingText: TextView
    private lateinit var minimapView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        // Recupera os elementos da tela
        loadingText = findViewById(R.id.textViewMap)
        minimapView = findViewById(R.id.minimap)

        // Aqui você pega o fragmento que já existe no XML
        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment

        // Quando a cena da AR começar a atualizar, some com o texto de "Carregando..."
        arFragment.arSceneView.scene.addOnUpdateListener {
            if (loadingText.isVisible) {
                loadingText.visibility = View.GONE
            }
        }

        // Aqui você pode usar minimapView como canvas depois pra desenhar o minimapa 2D no canto
    }
}
