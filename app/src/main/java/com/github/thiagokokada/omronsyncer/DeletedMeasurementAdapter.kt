package com.github.thiagokokada.omronsyncer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.thiagokokada.omronsyncer.databinding.ItemDeletedMeasurementBinding
import java.time.format.DateTimeFormatter

class DeletedMeasurementAdapter(
    private val onRestoreClick: (MeasurementListItem) -> Unit,
) : ListAdapter<MeasurementListItem, DeletedMeasurementAdapter.DeletedMeasurementViewHolder>(DiffCallback) {
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
        private val onRestoreClick: (MeasurementListItem) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MeasurementListItem) {
            val measurement = item.displayMeasurement
            binding.measurementSummary.text = binding.root.context.getString(
                R.string.restore_measurement_item,
                TIMESTAMP_FORMATTER.format(measurement.recordedAt),
                measurement.systolic,
                measurement.diastolic,
                measurement.pulse,
                measurement.flagsLabel(),
            )
            binding.restoreMeasurementButton.setOnClickListener {
                onRestoreClick(item)
            }
        }
    }

    private companion object {
        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        val DiffCallback = object : DiffUtil.ItemCallback<MeasurementListItem>() {
            override fun areItemsTheSame(oldItem: MeasurementListItem, newItem: MeasurementListItem): Boolean {
                val oldMeasurement = oldItem.displayMeasurement
                val newMeasurement = newItem.displayMeasurement
                return oldMeasurement.recordedAt == newMeasurement.recordedAt &&
                    oldMeasurement.user == newMeasurement.user &&
                    oldMeasurement.systolic == newMeasurement.systolic &&
                    oldMeasurement.diastolic == newMeasurement.diastolic &&
                    oldMeasurement.pulse == newMeasurement.pulse
            }

            override fun areContentsTheSame(oldItem: MeasurementListItem, newItem: MeasurementListItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
