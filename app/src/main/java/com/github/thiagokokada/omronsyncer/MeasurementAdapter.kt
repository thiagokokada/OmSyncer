package com.github.thiagokokada.omronsyncer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.thiagokokada.omronsyncer.databinding.ItemMeasurementBinding
import com.github.thiagokokada.omronsyncer.model.Measurement
import java.time.format.DateTimeFormatter

class MeasurementAdapter :
    ListAdapter<Measurement, MeasurementAdapter.MeasurementViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeasurementViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemMeasurementBinding.inflate(inflater, parent, false)
        return MeasurementViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MeasurementViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MeasurementViewHolder(
        private val binding: ItemMeasurementBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(measurement: Measurement) {
            binding.timeValue.text = TIMESTAMP_FORMATTER.format(measurement.recordedAt)
            binding.sysValue.text = measurement.systolic.toString()
            binding.diaValue.text = measurement.diastolic.toString()
            binding.pulseValue.text = measurement.pulse.toString()
            binding.flagsValue.text = measurement.flagsLabel()
            binding.userValue.text = measurement.user.toString()
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
