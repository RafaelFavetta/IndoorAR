package com.example.indoorar.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.indoorar.R
import com.example.indoorar.model.MapObject

class ObjectListAdapter(private val objects: List<MapObject>) :
    RecyclerView.Adapter<ObjectListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textName: TextView = view.findViewById(R.id.textObjectName)
        val textDetails: TextView = view.findViewById(R.id.textObjectDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_object, parent, false) // certifique-se que está "item_object"
        return ViewHolder(view)
    }


    override fun getItemCount(): Int = objects.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val obj = objects[position]
        holder.textName.text = obj.name ?: obj.type
        holder.textDetails.text = "(${obj.x}, ${obj.y})"
    }
}
