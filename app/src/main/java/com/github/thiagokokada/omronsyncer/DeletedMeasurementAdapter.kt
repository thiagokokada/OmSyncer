package com.github.thiagokokada.omronsyncer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.thiagokokada.omronsyncer.databinding.ItemDeletedMeasurementBinding
import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.format.DateTimeFormatter

class DeletedMeasurementAdapter(
    private val onRestoreClick: (Measurement) -> Unit,
) : ListAdapter<Measurement, DeletedMeasurementAdapter.DeletedMeasurementViewHolder>(DiffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeletedMeasurementViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemDeletedMeasurementBinding.inflate(inflater, parent, false)
        return DeletedMeasurementViewHolder(binding, onRestoreClick)
    }

    override fun onBindViewHolder(holder: DeletedMeasurementViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DeletedMeasurementViewHolder(
        private val binding: ItemDeletedMeasurementBinding,
        private val onRestoreClick: (Measurement) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(measurement: Measurement) {
            binding.measurementSummary.text = buildString {
                append(TIMESTAMP_FORMATTER.format(measurement.recordedAt))
                append(" · ")
                append(measurement.systolic)
                append('/')
                append(measurement.diastolic)
                append(", pulse ")
                append(measurement.pulse)
                append(", flags ")
                append(measurement.flagsLabel())
            }
            binding.restoreMeasurementButton.setOnClickListener {
                onRestoreClick(measurement)
            }
        }
    }

    private companion object {
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val DiffCallback = object : DiffUtil.ItemCallback<Measurement>() {
            override fun areItemsTheSame(oldItem: Measurement, newItem: Measurement): Boolean {
                return oldItem.recordedAt == newItem.recordedAt &&
                    oldItem.user == newItem.user &&
                    oldItem.systolic == newItem.systolic &&
                    oldItem.diastolic == newItem.diastolic &&
                    oldItem.pulse == newItem.pulse
            }

            override fun areContentsTheSame(oldItem: Measurement, newItem: Measurement): Boolean {
                return oldItem == newItem
            }
        }
    }
}
