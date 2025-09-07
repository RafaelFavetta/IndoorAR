package com.example.indoorar.ui

import android.app.Activity
import android.graphics.Color
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.example.indoorar.R
import com.example.indoorar.views.MapEditorView
import com.example.indoorar.views.ShapeProperties
import com.example.indoorar.ui.Action
import java.util.WeakHashMap
import kotlin.math.roundToInt

class AttributePanelController(
    private val activity: Activity,
    private val editor: MapEditorView
) : MapEditorView.OnShapeSelectionListener {

    private data class Meta(
        var nome: String = "",
        var descricao: String = "",
        var tipo: String = "",
        var corHex: String = "#D9D9D9",
        var rotacaoDeg: Float = 0f
    )
    private val metaStore = WeakHashMap<Action.Shape, Meta>()

    private var isProgrammatic = false

    // pega as views do layout (painel e edittexts)
    private val painel = activity.findViewById<View>(R.id.painelAtributos)

    private val edtNome = activity.findViewById<EditText>(R.id.inputNome)
    private val edtPosX = activity.findViewById<EditText>(R.id.inputX)
    private val edtPosY = activity.findViewById<EditText>(R.id.inputY)
    private val edtWidth = activity.findViewById<EditText>(R.id.inputWidth)
    private val edtHeight = activity.findViewById<EditText>(R.id.inputHeight)
    private val edtRotacao = activity.findViewById<EditText>(R.id.inputRotation)
    private val edtCor = activity.findViewById<EditText>(R.id.inputHex)

    init {
        editor.selectionListener = this
        painel.visibility = View.GONE
        setupFieldHandlers()
    }

    // ---- callbacks do MapEditorView ----
    override fun onShapeSelected(props: ShapeProperties) {
        val shape = editor.getSelectedShapeRef() ?: return
        val meta = metaStore.getOrPut(shape) { Meta() }

        isProgrammatic = true
        edtNome.setText(meta.nome)
        edtCor.setText(meta.corHex.uppercase())
        edtRotacao.setText(meta.rotacaoDeg.roundToInt().toString())

        edtPosX.setText(fmt(props.x))
        edtPosY.setText(fmt(props.y))
        edtWidth.setText(fmt(props.width))
        edtHeight.setText(fmt(props.height))

        painel.visibility = View.VISIBLE
        isProgrammatic = false
    }

    override fun onShapeDeselected() {
        painel.visibility = View.GONE
    }

    // ---- Handlers ----
    private fun setupFieldHandlers() {
        applyOnEdit(edtPosX) { pushPosition() }
        applyOnEdit(edtPosY) { pushPosition() }
        applyOnEdit(edtWidth) { pushSize() }
        applyOnEdit(edtHeight) { pushSize() }

        applyOnEdit(edtNome) { saveMeta() }
        applyOnEdit(edtRotacao) { saveMeta() }
        applyOnEdit(edtCor) {
            saveMeta()
            val txt = edtCor.text.toString().trim()
            if (isValidHex(txt)) {
                // preview de cor pode ser adicionado depois
            }
        }
    }

    private fun applyOnEdit(edit: EditText, action: () -> Unit) {
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) action()
        }
        edit.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (isDone) {
                action()
                true
            } else false
        }
    }

    private fun pushPosition() {
        if (isProgrammatic) return
        val props = editor.getSelectedShapeProperties() ?: return
        val x = edtPosX.text.toString().toFloatOrNull()
        val y = edtPosY.text.toString().toFloatOrNull()
        if (x != null && y != null) {
            editor.applyPropertiesToSelectedShape(props.copy(x = x, y = y))
        }
    }

    private fun pushSize() {
        if (isProgrammatic) return
        val props = editor.getSelectedShapeProperties() ?: return
        val w = edtWidth.text.toString().toFloatOrNull()
        val h = edtHeight.text.toString().toFloatOrNull()
        if (w != null && h != null) {
            editor.applyPropertiesToSelectedShape(props.copy(width = w, height = h))
        }
    }

    private fun saveMeta() {
        if (isProgrammatic) return
        val shape = editor.getSelectedShapeRef() ?: return
        val m = metaStore.getOrPut(shape) { Meta() }
        m.nome = edtNome.text.toString()
        m.corHex = edtCor.text.toString().ifBlank { "#D9D9D9" }
        m.rotacaoDeg = edtRotacao.text.toString().toFloatOrNull() ?: 0f
    }

    private fun isValidHex(s: String): Boolean {
        return try {
            Color.parseColor(s)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun fmt(v: Float): String {
        return String.format("%.1f", v)
    }
}
