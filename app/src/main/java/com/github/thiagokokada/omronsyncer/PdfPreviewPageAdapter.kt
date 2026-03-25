package com.github.thiagokokada.omronsyncer

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.thiagokokada.omronsyncer.databinding.ItemPdfPreviewPageBinding

class PdfPreviewPageAdapter : RecyclerView.Adapter<PdfPreviewPageAdapter.PdfPreviewPageViewHolder>() {

    private val pages = mutableListOf<Bitmap>()

    fun submitPages(newPages: List<Bitmap>) {
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPreviewPageViewHolder {
        val binding = ItemPdfPreviewPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return PdfPreviewPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PdfPreviewPageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class PdfPreviewPageViewHolder(
        private val binding: ItemPdfPreviewPageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(bitmap: Bitmap) {
            binding.pdfPageImage.setImageBitmap(bitmap)
        }
    }
}
