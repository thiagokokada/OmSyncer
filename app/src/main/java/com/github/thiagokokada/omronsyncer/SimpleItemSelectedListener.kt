package com.github.thiagokokada.omronsyncer

import android.view.View
import android.widget.AdapterView

class SimpleItemSelectedListener(
    private val onItemSelected: (position: Int) -> Unit,
) : AdapterView.OnItemSelectedListener {

    override fun onItemSelected(
        parent: AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long,
    ) {
        onItemSelected(position)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
