package com.nexustvguide.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexustvguide.app.R
import kotlinx.coroutines.launch
import org.threeten.bp.LocalDate
import org.threeten.bp.format.DateTimeFormatter

class ChannelOrderFragment : Fragment() {

    companion object {
        const val TAG = "ChannelOrderFragment"
        const val RESULT_KEY = "channel_order_closed"
        private const val ARG_DATE = "arg_date"

        fun newInstance(date: LocalDate): ChannelOrderFragment {
            return ChannelOrderFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_DATE, date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                }
            }
        }
    }

    private val viewModel: ChannelOrderViewModel by viewModels()
    private lateinit var adapter: ChannelOrderAdapter

    private lateinit var tvInstructions: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorContainer: View
    private lateinit var tvError: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnReset: Button
    private lateinit var btnDone: Button

    private var isCurrentlyGrabbed: Boolean = false
    private var initialFocusSet: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isCurrentlyGrabbed) {
                    val originalPos = viewModel.cancelGrab()
                    isCurrentlyGrabbed = false
                    updateInstructions(false)
                    if (originalPos != null) {
                        recyclerView.post {
                            val holder = recyclerView.findViewHolderForAdapterPosition(originalPos)
                            holder?.itemView?.requestFocus()
                        }
                    }
                } else {
                    navigateBack()
                }
            }
        })
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_channel_order, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvInstructions = view.findViewById(R.id.tv_channel_order_instructions)
        recyclerView = view.findViewById(R.id.rv_channel_order)
        progressBar = view.findViewById(R.id.channel_order_progress_bar)
        errorContainer = view.findViewById(R.id.channel_order_error_container)
        tvError = view.findViewById(R.id.tv_channel_order_error)
        btnRetry = view.findViewById(R.id.btn_retry_channel_order)
        btnReset = view.findViewById(R.id.btn_reset_order)
        btnDone = view.findViewById(R.id.btn_done)

        setupRecyclerView()
        setupButtons()

        val dateStr = arguments?.getString(ARG_DATE) ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }

        viewModel.loadChannels(dateStr)
    }

    private fun setupRecyclerView() {
        adapter = ChannelOrderAdapter(
            onItemGrabbed = { pos ->
                isCurrentlyGrabbed = true
                viewModel.grabItem(pos)
                updateInstructions(true)
            },
            onItemReleased = { pos ->
                isCurrentlyGrabbed = false
                viewModel.releaseItem(pos)
                updateInstructions(false)
            },
            onItemMove = { fromPos, toPos ->
                viewModel.moveItem(fromPos, toPos)
                adapter.notifyItemMoved(fromPos, toPos)
                adapter.notifyItemChanged(fromPos)
                adapter.notifyItemChanged(toPos)
                recyclerView.post {
                    val holder = recyclerView.findViewHolderForAdapterPosition(toPos)
                    holder?.itemView?.requestFocus()
                }
            },
            onVisibilityToggle = { channelId ->
                handleVisibilityToggle(channelId)
            }
        )

        recyclerView.isFocusable = false
        recyclerView.isFocusableInTouchMode = false
        recyclerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupButtons() {
        btnReset.setOnClickListener {
            showResetConfirmationDialog()
        }

        btnDone.setOnClickListener {
            navigateBack()
        }

        btnRetry.setOnClickListener {
            initialFocusSet = false
            val dateStr = arguments?.getString(ARG_DATE) ?: LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            viewModel.loadChannels(dateStr)
        }
    }

    private fun renderUiState(state: ChannelOrderUiState) {
        when (state) {
            is ChannelOrderUiState.Loading -> {
                progressBar.visibility = View.VISIBLE
                errorContainer.visibility = View.GONE
                recyclerView.visibility = View.GONE
            }
            is ChannelOrderUiState.Error -> {
                progressBar.visibility = View.GONE
                errorContainer.visibility = View.VISIBLE
                tvError.text = state.message
                recyclerView.visibility = View.GONE
            }
            is ChannelOrderUiState.Content -> {
                val needInitialFocus = !initialFocusSet
                progressBar.visibility = View.GONE
                errorContainer.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                isCurrentlyGrabbed = (state.grabbedPosition != null)
                updateInstructions(isCurrentlyGrabbed)
                adapter.submitList(state.items)

                if (needInitialFocus && state.items.isNotEmpty()) {
                    initialFocusSet = true
                    focusFirstItem()
                }
            }
        }
    }

    private fun updateInstructions(isGrabbed: Boolean) {
        if (isGrabbed) {
            tvInstructions.setText(R.string.channel_order_instructions_grabbed)
        } else {
            tvInstructions.setText(R.string.channel_order_instructions_normal)
        }
    }

    private fun handleVisibilityToggle(channelId: String) {
        val currentItems = adapter.getItems()
        val targetItem = currentItems.find { it.channel.id == channelId } ?: return

        // Als het item nu zichtbaar is en het is het LAATSTE zichtbare item, waarschuw de gebruiker
        if (!targetItem.isHidden) {
            val visibleCount = currentItems.count { !it.isHidden }
            if (visibleCount <= 1) {
                showHideLastChannelDialog(channelId)
                return
            }
        }

        viewModel.toggleVisibility(channelId)
    }

    private fun showHideLastChannelDialog(channelId: String) {
        AlertDialog.Builder(requireContext(), R.style.Theme_NexusTVGuide_Dialog)
            .setTitle(R.string.channel_order_hide_last_dialog_title)
            .setMessage(R.string.channel_order_hide_last_dialog_message)
            .setPositiveButton(R.string.channel_order_hide_last_dialog_confirm) { _, _ ->
                viewModel.toggleVisibility(channelId)
            }
            .setNegativeButton(R.string.channel_order_hide_last_dialog_cancel, null)
            .show()
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(requireContext(), R.style.Theme_NexusTVGuide_Dialog)
            .setTitle(R.string.channel_order_reset_dialog_title)
            .setMessage(R.string.channel_order_reset_dialog_message)
            .setPositiveButton(R.string.channel_order_reset_dialog_confirm) { _, _ ->
                viewModel.resetToDefault()
                focusFirstItem()
            }
            .setNegativeButton(R.string.channel_order_reset_dialog_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (initialFocusSet && adapter.itemCount > 0) {
            val currentFocused = view?.findFocus()
            if (currentFocused == null || currentFocused == view) {
                focusFirstItem()
            }
        }
    }

    private fun focusFirstItem() {
        if (!isAdded || view == null) return
        recyclerView.scrollToPosition(0)
        recyclerView.post {
            if (!isAdded || view == null) return@post
            val targetView = recyclerView.findViewHolderForAdapterPosition(0)?.itemView
                ?: recyclerView.layoutManager?.findViewByPosition(0)
            if (targetView != null) {
                targetView.requestFocus()
            } else {
                recyclerView.postDelayed({
                    if (!isAdded || view == null) return@postDelayed
                    val retryView = recyclerView.findViewHolderForAdapterPosition(0)?.itemView
                        ?: recyclerView.layoutManager?.findViewByPosition(0)
                    retryView?.requestFocus()
                }, 50)
            }
        }
    }

    private fun navigateBack() {
        setFragmentResult(RESULT_KEY, Bundle.EMPTY)
        parentFragmentManager.popBackStack()
    }
}
