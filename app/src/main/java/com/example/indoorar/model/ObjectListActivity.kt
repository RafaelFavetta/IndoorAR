package com.example.indoorar.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.indoorar.R
import com.example.indoorar.model.MapObject
import com.google.gson.Gson

class ObjectListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val objects = mutableListOf<MapObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_object_list)

        recyclerView = findViewById(R.id.recyclerObjects)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = ObjectListAdapter(objects)

        // Aqui você deve carregar os objetos salvos no editor
        loadDummyData()
    }

    private fun loadDummyData() {
        objects.add(MapObject("1", "rect", null, 0f, 0f, 2f, 1f, 0xFFFF0000.toInt()))
        objects.add(MapObject("2", "poi", "Sala A", 3f, 1f))
        objects.add(MapObject("3", "poi", "Sala B", 6f, 1f))
        recyclerView.adapter?.notifyDataSetChanged()
    }

    private fun exportObjectsToJson(): String {
        return Gson().toJson(objects)
    }

    private fun openARView() {
        val json = exportObjectsToJson()
        val intent = Intent(this, ARActivity::class.java)
        intent.putExtra("objects_json", json)
        startActivity(intent)
    }
}


