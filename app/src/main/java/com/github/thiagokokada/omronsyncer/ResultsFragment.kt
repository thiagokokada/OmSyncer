package com.github.thiagokokada.omronsyncer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.thiagokokada.omronsyncer.databinding.FragmentResultsBinding
import com.github.thiagokokada.omronsyncer.model.Measurement
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.toDrawable

class ResultsFragment : Fragment() {

    interface Host {
        fun currentUiState(): MainUiState
        fun onMeasurementDeleteRequested(measurement: Measurement)
        fun onSyncRequested()
    }

    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!
    private lateinit var host: Host
    private lateinit var adapter: MeasurementAdapter
    private lateinit var deleteSwipeBackground: ColorDrawable

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host
            ?: error("MainActivity must implement ResultsFragment.Host")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = MeasurementAdapter()
        deleteSwipeBackground = "#B3261E".toColorInt().toDrawable()
        binding.measurementsList.layoutManager = LinearLayoutManager(requireContext())
        binding.measurementsList.adapter = adapter
        ItemTouchHelper(DeleteMeasurementSwipeCallback()).attachToRecyclerView(binding.measurementsList)

        binding.syncButton.setOnClickListener {
            host.onSyncRequested()
        }
    }

    override fun onResume() {
        super.onResume()
        render(host.currentUiState())
    }

    fun render(state: MainUiState) {
        if (_binding == null) {
            return
        }

        adapter.setShowUserColumn(state.showsMeasurementUserColumn)
        adapter.submitList(state.measurements)

        binding.syncButton.isEnabled = state.canSync && !state.isWorking
        binding.syncButton.text = if (state.isWorking) {
            getString(R.string.syncing_button_label)
        } else {
            getString(R.string.sync_now)
        }

        binding.measurementCount.text = resources.getQuantityString(
            R.plurals.measurement_count,
            state.measurements.size,
            state.measurements.size,
        )

        val latestMeasurement = state.measurements.firstOrNull()
        binding.latestMeasurement.text = latestMeasurement?.let {
            getString(
                R.string.latest_measurement_summary,
                adapter.formatTimestamp(it.recordedAt),
                it.systolic,
                it.diastolic,
                it.pulse,
            )
        } ?: if (state.selectedMeasurementUser != null && state.measurementUserOptions.size > 1) {
            getString(R.string.no_measurement_summary_filtered)
        } else {
            getString(R.string.no_measurement_summary)
        }

        binding.emptyState.visibility =
            if (state.measurements.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyState.text = if (
            state.selectedMeasurementUser != null && state.measurementUserOptions.size > 1
        ) {
            getString(R.string.empty_measurements_filtered)
        } else {
            getString(R.string.empty_measurements)
        }
        binding.userColumnLabel.visibility =
            if (state.showsMeasurementUserColumn) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class DeleteMeasurementSwipeCallback : ItemTouchHelper.SimpleCallback(
        0,
        ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
    ) {
        private val deleteIcon = AppCompatResources.getDrawable(
            requireContext(),
            android.R.drawable.ic_menu_delete,
        )?.mutate()?.also { DrawableCompat.setTint(it, Color.WHITE) }

        override fun getSwipeDirs(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int {
            return if (host.currentUiState().isWorking) 0 else super.getSwipeDirs(recyclerView, viewHolder)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) {
                return
            }
            val measurement = adapter.currentList.getOrNull(position)
            if (measurement != null) {
                host.onMeasurementDeleteRequested(measurement)
            }
            adapter.notifyItemChanged(position)
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean,
        ) {
            val itemView = viewHolder.itemView
            if (dX < 0f) {
                deleteSwipeBackground.setBounds(
                    itemView.right + dX.toInt(),
                    itemView.top,
                    itemView.right,
                    itemView.bottom,
                )
                deleteSwipeBackground.draw(c)

                deleteIcon?.let { icon ->
                    val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconBottom = iconTop + icon.intrinsicHeight
                    val iconRight = itemView.right - iconMargin
                    val iconLeft = iconRight - icon.intrinsicWidth
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    icon.draw(c)
                }
            } else if (dX > 0f) {
                deleteSwipeBackground.setBounds(
                    itemView.left,
                    itemView.top,
                    itemView.left + dX.toInt(),
                    itemView.bottom,
                )
                deleteSwipeBackground.draw(c)

                deleteIcon?.let { icon ->
                    val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconBottom = iconTop + icon.intrinsicHeight
                    val iconLeft = itemView.left + iconMargin
                    val iconRight = iconLeft + icon.intrinsicWidth
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    icon.draw(c)
                }
            } else {
                deleteSwipeBackground.setBounds(0, 0, 0, 0)
            }

            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }
}
