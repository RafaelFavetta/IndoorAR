package com.example.indoorar.ui.editor

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.graphics.toColorInt
import com.example.indoorar.R
import com.example.indoorar.ui.Action
import java.util.Locale
import java.util.WeakHashMap
import com.google.android.material.switchmaterial.SwitchMaterial

class AttributePanelController(
    activity: Activity,
    private val editor: MapEditorView
) : MapEditorView.OnShapeSelectionListener {

    private data class Meta(
        var nome: String = "",
        var tipo: String = "",
        var corHex: String = "#D9D9D9"
    )

    private val metaStore = WeakHashMap<Action, Meta>()
    private var isProgrammatic = false

    private val painel = activity.findViewById<View>(R.id.painelAtributos)
    private val edtNome = activity.findViewById<EditText>(R.id.inputNome)
    private val edtPosX = activity.findViewById<EditText>(R.id.inputX)
    private val edtPosY = activity.findViewById<EditText>(R.id.inputY)
    private val edtWidth = activity.findViewById<EditText>(R.id.inputWidth)
    private val edtHeight = activity.findViewById<EditText>(R.id.inputHeight)
    private val edtCor = activity.findViewById<EditText>(R.id.inputHex)
    private val previewCor = activity.findViewById<View>(R.id.colorPreview)

    private val layoutStartQR = activity.findViewById<View>(R.id.layoutStartQR)
    private val switchIsStartQR = activity.findViewById<SwitchMaterial>(R.id.switchIsStartQR)
    private val layoutIsWalkable = activity.findViewById<View>(R.id.layoutIsWalkable)
    private val checkboxIsWalkable = activity.findViewById<android.widget.CheckBox>(R.id.checkboxIsWalkable)

    init {
        editor.selectionListener = this
        painel.visibility = View.GONE
        layoutStartQR?.visibility = View.GONE
        layoutIsWalkable?.visibility = View.GONE
        setupFieldHandlers()
        setupSwitch()
        setupWalkableCheckbox()
    }

    private fun setupSwitch() {
        switchIsStartQR?.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammatic) return@setOnCheckedChangeListener
            val poi = editor.actions.firstOrNull { it is Action.Poi && it.selected } as? Action.Poi
            poi?.isStartQR = isChecked
        }
    }

    private fun setupWalkableCheckbox() {
        checkboxIsWalkable?.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammatic) return@setOnCheckedChangeListener
            val shape = editor.actions.firstOrNull { it is Action.Shape && it.selected } as? Action.Shape
            shape?.isWalkable = isChecked
        }
    }

    // ===== MapEditorView callbacks =====
    override fun onShapeSelected(props: ShapeProps) {
        val obj = editor.actions.firstOrNull { (it is Action.Shape && it.selected) || (it is Action.Poi && it.selected) }
            ?: return
        val meta = metaStore.getOrPut(obj) {
            Meta(
                nome = when (obj) {
                    is Action.Shape -> obj.nome
                    else -> ""
                },
                corHex = if (obj is Action.Shape) String.format("#%06X", (0xFFFFFF and obj.fillColor)) else "#D9D9D9"
            )
        }

        isProgrammatic = true
        edtNome.setText(meta.nome)
        edtCor.setText(meta.corHex.uppercase())
        // Posição agora exibida em metros
        edtPosX.setText(fmt(editor.pxToMeters(props.x)))
        edtPosY.setText(fmt(editor.pxToMeters(props.y)))
        // Largura/altura exibidas em metros
        edtWidth.setText(fmt(editor.pxToMeters(props.width)))
        edtHeight.setText(fmt(editor.pxToMeters(props.height)))

        // Mostrar/ocultar seções conforme o tipo selecionado
        when (obj) {
            is Action.Poi -> {
                layoutStartQR?.visibility = View.VISIBLE
                switchIsStartQR?.isChecked = obj.isStartQR
                layoutIsWalkable?.visibility = View.GONE
                // Oculta campo de nome para POI
                edtNome?.visibility = View.GONE
            }
            is Action.Shape -> {
                layoutIsWalkable?.visibility = View.VISIBLE
                checkboxIsWalkable?.isChecked = obj.isWalkable
                layoutStartQR?.visibility = View.GONE
                // Exibe nome para Shape
                edtNome?.visibility = View.VISIBLE
            }
            else -> {
                layoutStartQR?.visibility = View.GONE
                layoutIsWalkable?.visibility = View.GONE
                edtNome?.visibility = View.GONE
            }
        }

        updateColorPreview(meta.corHex)
        painel.visibility = View.VISIBLE
        isProgrammatic = false
    }

    override fun onShapeDeselected() {
        painel.visibility = View.GONE
        layoutStartQR?.visibility = View.GONE
        layoutIsWalkable?.visibility = View.GONE
    }

    // ===== Field handlers =====
    private fun setupFieldHandlers() {
        applyOnEdit(edtPosX) { pushPosition() }
        applyOnEdit(edtPosY) { pushPosition() }
        applyOnEdit(edtWidth) { pushSize() }
        applyOnEdit(edtHeight) { pushSize() }
        applyOnEdit(edtNome) { saveMeta() }
        applyOnEdit(edtCor) { pushColor() }
    }

    private fun pushPosition() {
        if (isProgrammatic) return
        val obj = editor.actions.firstOrNull { (it is Action.Shape && it.selected) || (it is Action.Poi && it.selected) } ?: return

        // Entradas em metros -> converter para pixels
        val xMeters = edtPosX.text.toString().toFloatOrNull()
        val yMeters = edtPosY.text.toString().toFloatOrNull()
        val xPx = xMeters?.let { editor.metersToPx(it) }
        val yPx = yMeters?.let { editor.metersToPx(it) }

        when(obj) {
            is Action.Shape -> {
                val width = obj.end.x - obj.start.x
                val height = obj.end.y - obj.start.y
                if (xPx != null) obj.start.x = xPx
                if (yPx != null) obj.start.y = yPx
                obj.end.x = obj.start.x + width
                obj.end.y = obj.start.y + height
            }
            is Action.Poi -> {
                if (xPx != null) obj.x = xPx
                if (yPx != null) obj.y = yPx
            }
            is Action.BrushStroke -> {}
        }
        editor.invalidate()
    }

    private fun pushSize() {
        if (isProgrammatic) return
        val obj = editor.actions.firstOrNull { (it is Action.Shape && it.selected) || (it is Action.Poi && it.selected) } ?: return

        // Entradas são em metros; converter para pixels
        val wMeters = edtWidth.text.toString().toFloatOrNull()
        val hMeters = edtHeight.text.toString().toFloatOrNull()
        val wPx = wMeters?.let { editor.metersToPx(it) }
        val hPx = hMeters?.let { editor.metersToPx(it) }

        when(obj) {
            is Action.Shape -> {
                obj.end.x = obj.start.x + (wPx ?: (obj.end.x - obj.start.x))
                obj.end.y = obj.start.y + (hPx ?: (obj.end.y - obj.start.y))
            }
            is Action.Poi -> {
                obj.width = wPx ?: obj.width
                obj.height = hPx ?: obj.height
            }
            is Action.BrushStroke -> {} // nada
        }
        editor.invalidate()
    }

    private fun pushColor() {
        if (isProgrammatic) return
        val hex = edtCor.text.toString().ifBlank { "#D9D9D9" }
        val color = try { hex.toColorInt() } catch (_: Exception) { "#D9D9D9".toColorInt() }

        val obj = editor.actions.firstOrNull { (it is Action.Shape && it.selected) } as? Action.Shape
        obj?.fillColor = color
        editor.invalidate()
        updateColorPreview(hex)
        saveMeta()
    }

    private fun saveMeta() {
        if (isProgrammatic) return
        val obj = editor.actions.firstOrNull { (it is Action.Shape && it.selected) || (it is Action.Poi && it.selected) } ?: return
        val m = metaStore.getOrPut(obj) { Meta() }
        m.nome = edtNome.text.toString()
        m.corHex = edtCor.text.toString().ifBlank { "#D9D9D9" }
        // propaga direto para o objeto para garantir persistência no salvar
        when (obj) {
            is Action.Shape -> { obj.nome = m.nome }
            is Action.Poi -> { /* POI não possui nome/descrição */ }
            else -> {}
        }
    }

    private fun updateColorPreview(hex: String) {
        val color = try { hex.toColorInt() } catch (_: Exception) { "#D9D9D9".toColorInt() }
        previewCor.setBackgroundColor(color)
    }

    private fun fmt(v: Float): String {
        return if (v % 1.0 == 0.0) v.toInt().toString()
        else String.format(Locale.US, "%.2f", v)
    }

    private fun applyOnEdit(edit: EditText, action: () -> Unit) {
        edit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !isProgrammatic) action()
        }
        edit.setOnEditorActionListener { _, actionId, event ->
            val imeDone = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (imeDone && !isProgrammatic) {
                action()
                true
            } else false
        }
    }
}
