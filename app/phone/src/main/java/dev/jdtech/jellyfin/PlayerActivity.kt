package dev.jdtech.jellyfin

import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Rational
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.navigation.navArgs
import dagger.hilt.android.AndroidEntryPoint
import dev.jdtech.jellyfin.databinding.ActivityPlayerBinding
import dev.jdtech.jellyfin.dialogs.SpeedSelectionDialogFragment
import dev.jdtech.jellyfin.dialogs.TrackSelectionDialogFragment
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidSegmentType
import dev.jdtech.jellyfin.models.PlayerItem
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.PlayerGestureHelper
import dev.jdtech.jellyfin.utils.PreviewScrubListener
import dev.jdtech.jellyfin.viewmodels.PlayerActivityViewModel
import dev.jdtech.jellyfin.viewmodels.PlayerEvents
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import dev.jdtech.jellyfin.player.video.R as VideoR

var isControlsLocked: Boolean = false

@AndroidEntryPoint
class PlayerActivity : BasePlayerActivity() {

    @Inject
    lateinit var appPreferences: AppPreferences

    lateinit var binding: ActivityPlayerBinding
    private var playerGestureHelper: PlayerGestureHelper? = null
    override val viewModel: PlayerActivityViewModel by viewModels()
    private var previewScrubListener: PreviewScrubListener? = null
    private var wasZoom: Boolean = false
    private var segment: FindroidSegment? = null

    private lateinit var skipSegmentButton: Button

    private val isPipSupported by lazy {
        // Check if device has PiP feature
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return@lazy false
        }

        // Check if PiP is enabled for the app
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps?.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        } else {
            @Suppress("DEPRECATION")
            appOps?.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val skipButtonTimeout = Runnable {
        if (!binding.playerView.isControllerFullyVisible) {
            skipSegmentButton.isVisible = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val items = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.extras!!.getParcelableArrayList("items", PlayerItem::class.java)!!.toTypedArray()
        } else {
            @Suppress("DEPRECATION")
            intent.extras!!.getParcelableArrayList<PlayerItem>("items")!!.toTypedArray()
        }

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.playerView.player = viewModel.player
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.GONE) {
                    hideSystemUI()
                }
            },
        )

        val playerControls = binding.playerView.findViewById<View>(R.id.player_controls)
        val lockedControls = binding.playerView.findViewById<View>(R.id.locked_player_view)

        isControlsLocked = false

        configureInsets(playerControls)
        configureInsets(lockedControls)

        if (appPreferences.getValue(appPreferences.playerGestures)) {
            playerGestureHelper = PlayerGestureHelper(
                appPreferences,
                this,
                binding.playerView,
                getSystemService(AUDIO_SERVICE) as AudioManager,
            )
        }

        binding.playerView.findViewById<View>(R.id.back_button).setOnClickListener {
            finishPlayback()
        }

        val videoNameTextView = binding.playerView.findViewById<TextView>(R.id.video_name)

        val audioButton = binding.playerView.findViewById<ImageButton>(R.id.btn_audio_track)
        val subtitleButton = binding.playerView.findViewById<ImageButton>(R.id.btn_subtitle)
        val speedButton = binding.playerView.findViewById<ImageButton>(R.id.btn_speed)
        skipSegmentButton = binding.playerView.findViewById(R.id.btn_skip_segment)
        val pipButton = binding.playerView.findViewById<ImageButton>(R.id.btn_pip)
        val lockButton = binding.playerView.findViewById<ImageButton>(R.id.btn_lockview)
        val unlockButton = binding.playerView.findViewById<ImageButton>(R.id.btn_unlock)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { uiState ->
                        Timber.d("$uiState")
                        uiState.apply {
                            // Title
                            videoNameTextView.text = currentItemTitle

                            // Skip segment button
                            segment = currentSegment
                            currentSegment?.let { segment ->
                                // Button text
                                skipSegmentButton.text = when (segment.type) {
                                    FindroidSegmentType.INTRO -> getString(VideoR.string.player_controls_skip_intro)
                                    FindroidSegmentType.CREDITS -> getString(VideoR.string.player_controls_skip_credits)
                                    else -> ""
                                }
                                // Buttons visibility
                                skipSegmentButton.isVisible = segment.type != FindroidSegmentType.UNKNOWN && !isInPictureInPictureMode
                                if (skipSegmentButton.isVisible) {
                                    handler.removeCallbacks(skipButtonTimeout)
                                    handler.postDelayed(skipButtonTimeout, 5000)
                                }

                                // onClick
                                skipSegmentButton.setOnClickListener {
                                    binding.playerView.player?.seekTo((segment.endTime * 1000).toLong())
                                    skipSegmentButton.isVisible = false
                                }
                            } ?: run {
                                skipSegmentButton.isVisible = false
                            }

                            // Trickplay
                            previewScrubListener?.let {
                                it.currentTrickplay = currentTrickplay
                            }

                            playerGestureHelper?.let {
                                it.currentTrickplay = currentTrickplay
                            }

                            // Chapters
                            if (appPreferences.getValue(appPreferences.playerChapterMarkers) && currentChapters != null) {
                                currentChapters?.let { chapters ->
                                    val playerControlView = findViewById<PlayerControlView>(R.id.exo_controller)
                                    val numOfChapters = chapters.size
                                    playerControlView.setExtraAdGroupMarkers(
                                        LongArray(numOfChapters) { index -> chapters[index].startPosition },
                                        BooleanArray(numOfChapters) { false },
                                    )
                                }
                            }

                            // File Loaded
                            if (fileLoaded) {
                                audioButton.isEnabled = true
                                audioButton.imageAlpha = 255
                                lockButton.isEnabled = true
                                lockButton.imageAlpha = 255
                                subtitleButton.isEnabled = true
                                subtitleButton.imageAlpha = 255
                                speedButton.isEnabled = true
                                speedButton.imageAlpha = 255
                                pipButton.isEnabled = true
                                pipButton.imageAlpha = 255
                            }
                        }
                    }
                }

                launch {
                    viewModel.eventsChannelFlow.collect { event ->
                        when (event) {
                            is PlayerEvents.NavigateBack -> finishPlayback()
                            is PlayerEvents.IsPlayingChanged -> {
                                if (appPreferences.getValue(appPreferences.playerPipGesture)) {
                                    try {
                                        setPictureInPictureParams(pipParams(event.isPlaying))
                                    } catch (_: IllegalArgumentException) { }
                                }
                            }
                        }
                    }
                }
            }
        }

        audioButton.isEnabled = false
        audioButton.imageAlpha = 75

        lockButton.isEnabled = false
        lockButton.imageAlpha = 75

        subtitleButton.isEnabled = false
        subtitleButton.imageAlpha = 75

        speedButton.isEnabled = false
        speedButton.imageAlpha = 75

        if (isPipSupported) {
            pipButton.isEnabled = false
            pipButton.imageAlpha = 75
        } else {
            val pipSpace = binding.playerView.findViewById<Space>(R.id.space_pip)
            pipButton.isVisible = false
            pipSpace.isVisible = false
        }

        audioButton.setOnClickListener {
            TrackSelectionDialogFragment(C.TRACK_TYPE_AUDIO, viewModel).show(
                supportFragmentManager,
                "trackselectiondialog",
            )
        }

        val exoPlayerControlView = findViewById<FrameLayout>(R.id.player_controls)
        val lockedLayout = findViewById<FrameLayout>(R.id.locked_player_view)

        lockButton.setOnClickListener {
            exoPlayerControlView.visibility = View.GONE
            lockedLayout.visibility = View.VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            isControlsLocked = true
        }

        unlockButton.setOnClickListener {
            exoPlayerControlView.visibility = View.VISIBLE
            lockedLayout.visibility = View.GONE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            isControlsLocked = false
        }

        subtitleButton.setOnClickListener {
            TrackSelectionDialogFragment(C.TRACK_TYPE_TEXT, viewModel).show(
                supportFragmentManager,
                "trackselectiondialog",
            )
        }

        speedButton.setOnClickListener {
            SpeedSelectionDialogFragment(viewModel).show(
                supportFragmentManager,
                "speedselectiondialog",
            )
        }

        pipButton.setOnClickListener {
            pictureInPicture()
        }

        // Set marker color
        val timeBar = binding.playerView.findViewById<DefaultTimeBar>(R.id.exo_progress)
        timeBar.setAdMarkerColor(Color.WHITE)

        if (appPreferences.getValue(appPreferences.playerTrickplay)) {
            val imagePreview = binding.playerView.findViewById<ImageView>(R.id.image_preview)
            previewScrubListener = PreviewScrubListener(
                imagePreview,
                timeBar,
                viewModel.player,
            )

            timeBar.addListener(previewScrubListener!!)
        }

        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (segment != null) {
                    skipSegmentButton.visibility = visibility
                }
            },
        )

        viewModel.initializePlayer(items)
        hideSystemUI()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val args: PlayerActivityArgs by navArgs()
        viewModel.initializePlayer(args.items)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            appPreferences.getValue(appPreferences.playerPipGesture) &&
            viewModel.player.isPlaying &&
            !isControlsLocked
        ) {
            pictureInPicture()
        }
    }

    private fun finishPlayback() {
        try {
            viewModel.player.clearVideoSurfaceView(binding.playerView.videoSurfaceView as SurfaceView)
        } catch (e: Exception) {
            Timber.e(e)
        }
        finish()
    }

    private fun pipParams(enableAutoEnter: Boolean = viewModel.player.isPlaying): PictureInPictureParams {
        val displayAspectRatio = Rational(binding.playerView.width, binding.playerView.height)

        val aspectRatio = binding.playerView.player?.videoSize?.let {
            Rational(
                it.width.coerceAtMost((it.height * 2.39f).toInt()),
                it.height.coerceAtMost((it.width * 2.39f).toInt()),
            )
        }

        val sourceRectHint = if (displayAspectRatio < aspectRatio!!) {
            val space = ((binding.playerView.height - (binding.playerView.width.toFloat() / aspectRatio.toFloat())) / 2).toInt()
            Rect(
                0,
                space,
                binding.playerView.width,
                (binding.playerView.width.toFloat() / aspectRatio.toFloat()).toInt() + space,
            )
        } else {
            val space = ((binding.playerView.width - (binding.playerView.height.toFloat() * aspectRatio.toFloat())) / 2).toInt()
            Rect(
                space,
                0,
                (binding.playerView.height.toFloat() * aspectRatio.toFloat()).toInt() + space,
                binding.playerView.height,
            )
        }

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setSourceRectHint(sourceRectHint)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(enableAutoEnter)
        }

        return builder.build()
    }

    private fun pictureInPicture() {
        if (!isPipSupported) {
            return
        }

        try {
            enterPictureInPictureMode(pipParams())
        } catch (_: IllegalArgumentException) { }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        when (isInPictureInPictureMode) {
            true -> {
                binding.playerView.useController = false
                skipSegmentButton.isVisible = false

                wasZoom = playerGestureHelper?.isZoomEnabled == true
                playerGestureHelper?.updateZoomMode(false)

                // Brightness mode Auto
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
            false -> {
                binding.playerView.useController = true
                playerGestureHelper?.updateZoomMode(wasZoom)

                // Override auto brightness
                window.attributes = window.attributes.apply {
                    screenBrightness = if (appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember)) {
                        appPreferences.getValue(appPreferences.playerBrightness)
                    } else {
                        Settings.System.getInt(
                            contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                        ).toFloat() / 255
                    }
                }
            }
        }
    }
}
