package com.github.thiagokokada.omronsyncer

import android.view.View
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

    private var showUserColumn: Boolean = true

    fun setShowUserColumn(show: Boolean) {
        if (showUserColumn != show) {
            showUserColumn = show
            if (itemCount > 0) {
                notifyItemRangeChanged(0, itemCount, PAYLOAD_USER_COLUMN_VISIBILITY)
            }
        }
    }

    fun formatTimestamp(recordedAt: java.time.LocalDateTime): String {
        return TIMESTAMP_FORMATTER.format(recordedAt)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeasurementViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemMeasurementBinding.inflate(inflater, parent, false)
        return MeasurementViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MeasurementViewHolder, position: Int) {
        holder.bind(getItem(position), showUserColumn)
    }

    override fun onBindViewHolder(
        holder: MeasurementViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(PAYLOAD_USER_COLUMN_VISIBILITY)) {
            holder.updateUserColumn(getItem(position).user, showUserColumn)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    class MeasurementViewHolder(
        private val binding: ItemMeasurementBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(measurement: Measurement, showUserColumn: Boolean) {
            binding.timeValue.text = TIMESTAMP_FORMATTER.format(measurement.recordedAt)
            binding.sysValue.text = measurement.systolic.toString()
            binding.diaValue.text = measurement.diastolic.toString()
            binding.pulseValue.text = measurement.pulse.toString()
            binding.flagsValue.text = measurement.flagsLabel()
            updateUserColumn(measurement.user, showUserColumn)
        }

        fun updateUserColumn(user: Int, showUserColumn: Boolean) {
            binding.userValue.text = user.toString()
            binding.userValue.visibility = if (showUserColumn) View.VISIBLE else View.GONE
        }
    }

    private companion object {
        const val PAYLOAD_USER_COLUMN_VISIBILITY = "user_column_visibility"
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
