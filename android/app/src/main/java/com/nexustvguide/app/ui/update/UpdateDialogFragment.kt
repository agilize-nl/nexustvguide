package com.nexustvguide.app.ui.update

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.R
import kotlinx.coroutines.launch

class UpdateDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "UpdateDialogFragment"

        fun newInstance(): UpdateDialogFragment {
            return UpdateDialogFragment()
        }
    }

    private val viewModel: UpdateViewModel by activityViewModels()

    private lateinit var titleText: TextView
    private lateinit var versionContainer: View
    private lateinit var currentVersionBadge: TextView
    private lateinit var newVersionBadge: TextView
    private lateinit var sizeBadge: TextView
    private lateinit var releaseNotesLabel: TextView
    private lateinit var releaseNotesScroll: ScrollView
    private lateinit var releaseNotesText: TextView
    private lateinit var statusMessageText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var spinner: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnPrimary: Button
    private lateinit var btnSecondary: Button

    var onDismissCallback: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_app_update, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        titleText = view.findViewById(R.id.update_dialog_title)
        versionContainer = view.findViewById(R.id.update_version_container)
        currentVersionBadge = view.findViewById(R.id.update_current_version_badge)
        newVersionBadge = view.findViewById(R.id.update_new_version_badge)
        sizeBadge = view.findViewById(R.id.update_size_badge)
        releaseNotesLabel = view.findViewById(R.id.update_release_notes_label)
        releaseNotesScroll = view.findViewById(R.id.update_release_notes_scroll)
        releaseNotesText = view.findViewById(R.id.update_release_notes_text)
        statusMessageText = view.findViewById(R.id.update_status_message)
        progressBar = view.findViewById(R.id.update_progress_bar)
        spinner = view.findViewById(R.id.update_spinner)
        progressText = view.findViewById(R.id.update_progress_text)
        btnPrimary = view.findViewById(R.id.update_btn_primary)
        btnSecondary = view.findViewById(R.id.update_btn_secondary)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.navEvents.collect { event ->
                    if (event is UpdateNavigationEvent.DismissDialog) {
                        dismissAllowingStateLoss()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumeFromSettings()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }

    private fun renderState(state: UpdateUiState) {
        when (state) {
            is UpdateUiState.Idle -> {
                dismissAllowingStateLoss()
            }

            is UpdateUiState.Checking -> {
                titleText.text = getString(R.string.update_dialog_title_checking)
                versionContainer.visibility = View.GONE
                releaseNotesLabel.visibility = View.GONE
                releaseNotesScroll.visibility = View.GONE
                statusMessageText.visibility = View.GONE
                progressBar.visibility = View.GONE
                spinner.visibility = View.VISIBLE
                progressText.visibility = View.GONE

                btnPrimary.visibility = View.GONE
                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = getString(R.string.update_btn_cancel)
                btnSecondary.setOnClickListener {
                    viewModel.dismiss()
                }
                btnSecondary.requestFocus()
            }

            is UpdateUiState.UpdateAvailable -> {
                val meta = state.metadata
                titleText.text = getString(R.string.update_dialog_title_available, meta.versionName)
                versionContainer.visibility = View.VISIBLE
                currentVersionBadge.text = "${getString(R.string.update_current_version_label)} v${BuildConfig.VERSION_NAME}"
                newVersionBadge.text = "${getString(R.string.update_new_version_label)} v${meta.versionName}"
                val sizeMb = String.format("%.1f MB", meta.fileSizeBytes / (1024.0 * 1024.0))
                sizeBadge.text = sizeMb

                releaseNotesLabel.visibility = View.VISIBLE
                releaseNotesScroll.visibility = View.VISIBLE
                releaseNotesText.text = meta.releaseNotes ?: getString(R.string.update_no_release_notes)

                statusMessageText.visibility = View.GONE
                progressBar.visibility = View.GONE
                spinner.visibility = View.GONE
                progressText.visibility = View.GONE

                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = getString(R.string.update_btn_later)
                btnSecondary.setOnClickListener {
                    viewModel.snooze(meta.versionCode)
                }

                btnPrimary.visibility = View.VISIBLE
                btnPrimary.text = getString(R.string.update_btn_download_install)
                btnPrimary.setOnClickListener {
                    viewModel.startDownload(meta)
                }
                btnPrimary.requestFocus()
            }

            is UpdateUiState.Downloading -> {
                val meta = state.metadata
                titleText.text = getString(R.string.update_dialog_title_downloading)
                versionContainer.visibility = View.VISIBLE
                releaseNotesLabel.visibility = View.GONE
                releaseNotesScroll.visibility = View.GONE
                statusMessageText.visibility = View.GONE
                spinner.visibility = View.GONE

                progressBar.visibility = View.VISIBLE
                progressBar.progress = state.percent

                progressText.visibility = View.VISIBLE
                val readMb = String.format("%.1f", state.bytesRead / (1024.0 * 1024.0))
                val totalMb = String.format("%.1f", state.totalBytes / (1024.0 * 1024.0))
                progressText.text = "$readMb MB / $totalMb MB (${state.percent}%)"

                btnPrimary.visibility = View.GONE
                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = getString(R.string.update_btn_cancel)
                btnSecondary.setOnClickListener {
                    viewModel.cancelDownload()
                }
                btnSecondary.requestFocus()
            }

            is UpdateUiState.Verifying -> {
                titleText.text = getString(R.string.update_dialog_title_verifying)
                versionContainer.visibility = View.VISIBLE
                releaseNotesLabel.visibility = View.GONE
                releaseNotesScroll.visibility = View.GONE
                statusMessageText.visibility = View.GONE
                progressBar.visibility = View.GONE
                spinner.visibility = View.VISIBLE
                progressText.visibility = View.GONE

                btnPrimary.visibility = View.GONE
                btnSecondary.visibility = View.GONE
            }

            is UpdateUiState.PermissionRequired -> {
                titleText.text = getString(R.string.update_dialog_title_permission)
                versionContainer.visibility = View.GONE
                releaseNotesLabel.visibility = View.GONE
                releaseNotesScroll.visibility = View.GONE
                statusMessageText.visibility = View.VISIBLE
                statusMessageText.text = getString(R.string.update_permission_explanation)
                progressBar.visibility = View.GONE
                spinner.visibility = View.GONE
                progressText.visibility = View.GONE

                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = getString(R.string.update_btn_cancel)
                btnSecondary.setOnClickListener {
                    viewModel.dismiss()
                }

                btnPrimary.visibility = View.VISIBLE
                btnPrimary.text = getString(R.string.update_btn_open_settings)
                btnPrimary.setOnClickListener {
                    try {
                        startActivity(state.settingsIntent)
                    } catch (_: Exception) {
                        statusMessageText.text = "Kan instellingen niet direct openen. Ga naar Systeeminstellingen > Beveiliging > Onbekende bronnen."
                    }
                }
                btnPrimary.requestFocus()
            }

            is UpdateUiState.ReadyToInstall -> {
                val meta = state.metadata
                titleText.text = getString(R.string.update_dialog_title_ready)
                versionContainer.visibility = View.VISIBLE
                releaseNotesLabel.visibility = View.GONE
                releaseNotesScroll.visibility = View.GONE
                statusMessageText.visibility = View.GONE
                progressBar.visibility = View.GONE
                spinner.visibility = View.GONE
                progressText.visibility = View.GONE

                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = getString(R.string.update_btn_cancel)
                btnSecondary.setOnClickListener {
                    viewModel.dismiss()
                }

                btnPrimary.visibility = View.VISIBLE
                btnPrimary.text = getString(R.string.update_btn_install)
                btnPrimary.setOnClickListener {
                    viewModel.commitInstallation(requireContext(), state.apkFile, meta)
                }
                btnPrimary.requestFocus()
            }

            is UpdateUiState.WaitingForSystemDialog -> {
                titleText.text = getString(R.string.update_dialog_title_ready)
                versionContainer.visibility = View.GONE
                releaseNotesLabel.visibility = View.GONE
                releaseNotesScroll.visibility = View.GONE
                statusMessageText.visibility = View.VISIBLE
                statusMessageText.text = getString(R.string.update_waiting_system_dialog)
                progressBar.visibility = View.GONE
                spinner.visibility = View.VISIBLE
                progressText.visibility = View.GONE

                btnPrimary.visibility = View.GONE
                btnSecondary.visibility = View.GONE
            }

            is UpdateUiState.UpToDate -> {
                titleText.text = getString(R.string.update_dialog_title_uptodate)
                versionContainer.visibility = View.GONE
                releaseNotesLabel.visibility = View.GONE
                releaseNotesScroll.visibility = View.GONE
                statusMessageText.visibility = View.VISIBLE
                statusMessageText.text = getString(R.string.update_uptodate_message, state.versionName)
                progressBar.visibility = View.GONE
                spinner.visibility = View.GONE
                progressText.visibility = View.GONE

                btnSecondary.visibility = View.GONE
                btnPrimary.visibility = View.VISIBLE
                btnPrimary.text = getString(R.string.update_btn_ok)
                btnPrimary.setOnClickListener {
                    viewModel.dismiss()
                }
                btnPrimary.requestFocus()
            }

            is UpdateUiState.Error -> {
                titleText.text = state.title
                versionContainer.visibility = View.GONE
                releaseNotesLabel.visibility = View.GONE
                releaseNotesScroll.visibility = View.GONE
                statusMessageText.visibility = View.VISIBLE
                statusMessageText.text = state.message
                progressBar.visibility = View.GONE
                spinner.visibility = View.GONE
                progressText.visibility = View.GONE

                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = getString(R.string.update_btn_close)
                btnSecondary.setOnClickListener {
                    viewModel.dismiss()
                }

                if (state.canRetry && state.metadata != null) {
                    btnPrimary.visibility = View.VISIBLE
                    btnPrimary.text = getString(R.string.update_btn_retry)
                    btnPrimary.setOnClickListener {
                        viewModel.startDownload(state.metadata)
                    }
                    btnPrimary.requestFocus()
                } else if (state.canRetry) {
                    btnPrimary.visibility = View.VISIBLE
                    btnPrimary.text = getString(R.string.update_btn_retry)
                    btnPrimary.setOnClickListener {
                        viewModel.checkForUpdates(isManual = true)
                    }
                    btnPrimary.requestFocus()
                } else {
                    btnPrimary.visibility = View.GONE
                    btnSecondary.requestFocus()
                }
            }
        }
    }
}
