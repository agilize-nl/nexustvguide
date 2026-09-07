package com.nexustvguide.app.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.egeniq.androidtvprogramguide.ProgramGuideFragment
import com.egeniq.androidtvprogramguide.R as LibraryR
import com.egeniq.androidtvprogramguide.entity.ProgramGuideChannel
import com.egeniq.androidtvprogramguide.entity.ProgramGuideSchedule
import com.egeniq.androidtvprogramguide.util.ProgramGuideUtil
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.data.repository.GuideRepositoryProvider
import kotlinx.coroutines.delay
import com.nexustvguide.app.R
import com.nexustvguide.app.data.model.ProgrammeDto
import com.nexustvguide.app.ui.update.UpdateDialogFragment
import com.nexustvguide.app.ui.update.UpdateNavigationEvent
import com.nexustvguide.app.ui.update.UpdateViewModel
import com.nexustvguide.app.util.NlzietLauncher
import kotlinx.coroutines.launch
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter
import java.util.Locale

class NexusProgramGuideFragment : ProgramGuideFragment<ProgrammeDto>() {

    companion object {
        private const val TAG = "NexusProgramGuide"
    }

    override val DISPLAY_LOCALE: Locale = Locale("nl", "NL")
    override val DISPLAY_TIMEZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
    override val SELECTABLE_DAYS_IN_PAST: Int = 1
    // Egeniq gebruikt `until` (exclusief bovengrens): 8 toont vandaag t/m +7 dagen.
    override val SELECTABLE_DAYS_IN_FUTURE: Int = 8
    override val DISPLAY_CURRENT_TIME_INDICATOR: Boolean = true
    override val DISPLAY_MENU_BUTTON: Boolean = true
    override val USE_HUMAN_DATES: Boolean = true
    override val DATE_WITH_DAY_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE d MMMM", Locale("nl", "NL"))

    private val viewModel: GuideViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by activityViewModels()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private var skipNextResumeRefresh = false
    private var lastRenderedState: GuideUiState.Content? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ProgramGuideFragment is timezone-neutral by default; initialize the first request in
        // the guide's explicit Amsterdam timezone as well.
        currentDate = currentDateInDisplayTimeZone()
        setFragmentResultListener(ChannelOrderFragment.RESULT_KEY) { _, _ ->
            skipNextResumeRefresh = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is GuideUiState.Loading -> {
                            setState(State.Loading)
                        }
                        is GuideUiState.Content -> {
                            val isSame = lastRenderedState?.date == state.date &&
                                    lastRenderedState?.channels == state.channels &&
                                    lastRenderedState?.schedulesByChannel == state.schedulesByChannel
                            if (!isSame) {
                                lastRenderedState = state
                                setData(state.channels, state.schedulesByChannel, state.date)
                            }
                            setState(State.Content)
                            if (state.isStale) {
                                Log.i(TAG, "Displaying stale guide snapshot for ${state.date}")
                            }
                        }
                        is GuideUiState.AllChannelsHidden -> {
                            lastRenderedState = null
                            setData(emptyList(), emptyMap(), state.date)
                            setState(State.Error(getString(R.string.programguide_all_channels_hidden)))
                        }
                        is GuideUiState.Error -> {
                            lastRenderedState = null
                            setState(State.Error(state.message))
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    while (true) {
                        delay(60_000)
                        viewModel.loadGuideForDate(currentDate)
                    }
                }
                updateViewModel.navEvents.collect { event ->
                    if (event is UpdateNavigationEvent.ShowUpdateDialog) {
                        showUpdateDialog()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog() {
        if (childFragmentManager.findFragmentByTag(UpdateDialogFragment.TAG) == null &&
            parentFragmentManager.findFragmentByTag(UpdateDialogFragment.TAG) == null
        ) {
            val dialog = UpdateDialogFragment.newInstance()
            dialog.show(parentFragmentManager, UpdateDialogFragment.TAG)
        }
    }

    override fun onMenuButtonClicked(anchor: View) {
        val menuItems = arrayOf(
            getString(R.string.menu_item_check_updates),
            getString(R.string.menu_item_channel_order),
            getString(R.string.menu_item_guide_source),
            getString(R.string.menu_item_about)
        )

        var navigated = false

        AlertDialog.Builder(requireContext(), R.style.Theme_NexusTVGuide_Dialog)
            .setTitle(R.string.menu_title)
            .setItems(menuItems) { _, which ->
                when (which) {
                    0 -> {
                        updateViewModel.checkForUpdates(isManual = true)
                    }
                    1 -> {
                        navigated = true
                        (activity as? MainActivity)?.showChannelOrder(currentDate)
                    }
                    2 -> showGuideSourceDialog(anchor)
                    3 -> showAboutDialog(anchor)
                }
            }
            .setOnDismissListener {
                if (!navigated && anchor.isShown) {
                    anchor.post { anchor.requestFocus() }
                }
            }
            .show()
    }

    private fun showGuideSourceDialog(anchor: View) {
        val sources = arrayOf("LOCAL", "REMOTE")
        val selected = sources.indexOf(GuideRepositoryProvider.getGuideSource(requireContext()))
        AlertDialog.Builder(requireContext(), R.style.Theme_NexusTVGuide_Dialog)
            .setTitle(R.string.menu_item_guide_source)
            .setSingleChoiceItems(R.array.guide_sources, selected) { dialog, which ->
                GuideRepositoryProvider.setGuideSource(requireContext(), sources[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { anchor.post { anchor.requestFocus() } }
            .show()
    }

    private fun showAboutDialog(anchor: View) {
        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_NexusTVGuide_Dialog)
            .setIcon(R.drawable.app_logo)
            .setTitle(R.string.about_dialog_title)
            .setMessage(getString(R.string.about_dialog_message, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
            .setPositiveButton(R.string.update_btn_ok, null)
            .setOnDismissListener {
                if (anchor.isShown) {
                    anchor.post { anchor.requestFocus() }
                }
            }
            .show()

        dialog.findViewById<TextView>(android.R.id.message)?.isFocusable = true
    }

    override fun requestingProgramGuideFor(localDate: LocalDate) {
        viewModel.loadGuideForDate(localDate)
    }

    override fun requestRefresh() {
        viewModel.loadGuideForDate(currentDate)
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false
        } else {
            viewModel.loadGuideForDate(currentDate)
        }
        if (currentState is State.Content) {
            programGuideGrid.restoreSelection(ProgramGuideUtil.lastClickedSchedule)
        }
    }

    override fun isTopMenuVisible(): Boolean = false

    override fun onScheduleSelected(programGuideSchedule: ProgramGuideSchedule<ProgrammeDto>?) {
        val titleView = view?.findViewById<TextView>(LibraryR.id.programguide_detail_title)
        val metadataView = view?.findViewById<TextView>(LibraryR.id.programguide_detail_metadata)
        val descriptionView = view?.findViewById<TextView>(LibraryR.id.programguide_detail_description)
        val imageView = view?.findViewById<ImageView>(LibraryR.id.programguide_detail_image)

        val prog = programGuideSchedule?.program
        if (prog != null) {
            titleView?.text = prog.title

            val startZdt = Instant.ofEpochMilli(programGuideSchedule.startsAtMillis).atZone(DISPLAY_TIMEZONE)
            val endZdt = Instant.ofEpochMilli(programGuideSchedule.endsAtMillis).atZone(DISPLAY_TIMEZONE)
            val timeText = "${timeFormatter.format(startZdt)} - ${timeFormatter.format(endZdt)}"

            val badges = mutableListOf<String>()
            badges.add(timeText)

            if (prog.isLive) {
                badges.add("LIVE")
            }
            if (prog.isPremiere) {
                badges.add("Première")
            }
            if (prog.isRerun) {
                badges.add("Herhaling")
            }
            if (!prog.genre.isNullOrBlank()) {
                badges.add(prog.genre)
            }
            if (!prog.ageRating.isNullOrBlank()) {
                badges.add("[${prog.ageRating}]")
            }

            metadataView?.text = badges.joinToString(" • ")
            descriptionView?.text = prog.description

            if (imageView != null) {
                if (!prog.imageUrl.isNullOrBlank()) {
                    Glide.with(imageView)
                        .load(prog.imageUrl)
                        .centerCrop()
                        .transition(withCrossFade())
                        .into(imageView)
                } else {
                    Glide.with(imageView).clear(imageView)
                }
            }
        } else {
            titleView?.text = null
            metadataView?.text = null
            descriptionView?.text = null
            if (imageView != null) {
                Glide.with(imageView).clear(imageView)
            }
        }
    }

    override fun onScheduleClicked(programGuideSchedule: ProgramGuideSchedule<ProgrammeDto>) {
        Log.i("NexusProgramGuide", "onScheduleClicked: [id=${programGuideSchedule.id}, title='${programGuideSchedule.program?.title}', channelId='${programGuideSchedule.program?.channelId}', startMillis=${programGuideSchedule.startsAtMillis}, endsMillis=${programGuideSchedule.endsAtMillis}]")
        NlzietLauncher.launchProgramme(requireContext(), programGuideSchedule.program)
    }

    override fun onChannelClicked(channel: ProgramGuideChannel) {
        NlzietLauncher.launchChannel(requireContext(), channel.name)
    }

    override fun onChannelSelected(channel: ProgramGuideChannel) {
        val titleView = view?.findViewById<TextView>(LibraryR.id.programguide_detail_title)
        titleView?.text = channel.name
        val metadataView = view?.findViewById<TextView>(LibraryR.id.programguide_detail_metadata)
        metadataView?.text = null
        val descriptionView = view?.findViewById<TextView>(LibraryR.id.programguide_detail_description)
        descriptionView?.text = null
        val imageView = view?.findViewById<ImageView>(LibraryR.id.programguide_detail_image) ?: return
        Glide.with(imageView).clear(imageView)
    }
}
