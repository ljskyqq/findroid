package dev.jdtech.jellyfin.utils

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import dev.jdtech.jellyfin.PlayerActivity
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.isControlsLocked
import dev.jdtech.jellyfin.models.PlayerChapter
import dev.jdtech.jellyfin.models.Trickplay
import dev.jdtech.jellyfin.mpv.MPVPlayer
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import kotlinx.coroutines.Dispatchers
import timber.log.Timber
import kotlin.math.abs

class PlayerGestureHelper(
    private val appPreferences: AppPreferences,
    private val activity: PlayerActivity,
    private val playerView: PlayerView,
    private val audioManager: AudioManager,
) {
    /**
     * Tracks whether video content should fill the screen, cutting off unwanted content on the sides.
     * Useful on wide-screen phones to remove black bars from some movies.
     */
    var isZoomEnabled = false

    /**
     * Tracks a value during a swipe gesture (between multiple onScroll calls).
     * When the gesture starts it's reset to an initial value and gets increased or decreased
     * (depending on the direction) as the gesture progresses.
     */

    private var swipeGestureValueTrackerVolume = -1f
    private var swipeGestureValueTrackerBrightness = -1f
    private var swipeGestureValueTrackerProgress = -1L

    private var swipeGestureVolumeOpen = false
    private var swipeGestureBrightnessOpen = false
    private var swipeGestureProgressOpen = false

    private var lastScaleEvent: Long = 0

    private var playbackSpeedIncrease: Float = 2f
    private var lastPlaybackSpeed: Float = 0f

    private val screenWidth = Resources.getSystem().displayMetrics.widthPixels
    private val screenHeight = Resources.getSystem().displayMetrics.heightPixels

    var currentTrickplay: Trickplay? = null
    private val trickplayRoundedCorners = RoundedCornersTransformation(10f)
    private var currentTrickplayBitmap: Bitmap? = null

    private var currentNumberOfPointers: Int = 0

    private val tapGestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.apply {
                    if (!isControllerFullyVisible) showController() else hideController()
                }

                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Disables long press gesture if view is locked
                if (isControlsLocked) return

                // Stop long press gesture when more than 1 pointer
                if (currentNumberOfPointers > 1) return

                // This is a temporary solution for chapter skipping.
                // TODO: Remove this after implementing #636
                if (appPreferences.getValue(appPreferences.playerGesturesChapterSkip)) {
                    handleChapterSkip(e)
                } else {
                    enableSpeedIncrease()
                }
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Disables double tap gestures if view is locked
                if (isControlsLocked) return false

                val viewWidth = playerView.measuredWidth
                val areaWidth = viewWidth / 5 // Divide the view into 5 parts: 2:1:2

                // Define the areas and their boundaries
                val leftmostAreaStart = 0
                val middleAreaStart = areaWidth * 2
                val rightmostAreaStart = middleAreaStart + areaWidth

                when (e.x.toInt()) {
                    in leftmostAreaStart until middleAreaStart -> {
                        // Tapped on the leftmost area (seek backward)
                        rewind()
                    }
                    in middleAreaStart until rightmostAreaStart -> {
                        // Tapped on the middle area (toggle pause/unpause)
                        togglePlayback()
                    }
                    in rightmostAreaStart until viewWidth -> {
                        // Tapped on the rightmost area (seek forward)
                        fastForward()
                    }
                }
                return true
            }
        },
    )

    @SuppressLint("SetTextI18n")
    private fun enableSpeedIncrease() {
        playerView.player?.let {
            if (it.isPlaying) {
                lastPlaybackSpeed = it.playbackParameters.speed
                it.setPlaybackSpeed(playbackSpeedIncrease)
                activity.binding.gestureSpeedText.text = playbackSpeedIncrease.toString() + "x"
                activity.binding.gestureSpeedLayout.visibility = View.VISIBLE
            }
        }
    }

    private fun handleChapterSkip(e: MotionEvent) {
        if (isControlsLocked) {
            return
        }

        val viewWidth = playerView.measuredWidth
        val areaWidth = viewWidth / 5 // Divide the view into 5 parts: 2:1:2

        // Define the areas and their boundaries
        val leftmostAreaStart = 0
        val middleAreaStart = areaWidth * 2
        val rightmostAreaStart = middleAreaStart + areaWidth

        when (e.x.toInt()) {
            in leftmostAreaStart until middleAreaStart -> {
                activity.viewModel.seekToPreviousChapter()?.let { chapter ->
                    displayChapter(chapter)
                }
            }
            in rightmostAreaStart until viewWidth -> {
                if (activity.viewModel.isLastChapter() == true) {
                    playerView.player?.seekToNextMediaItem()
                    return
                }
                activity.viewModel.seekToNextChapter()?.let { chapter ->
                    displayChapter(chapter)
                }
            }
            else -> return
        }
    }

    private fun displayChapter(chapter: PlayerChapter) {
        activity.binding.progressScrubberLayout.visibility = View.VISIBLE
        activity.binding.progressScrubberText.text = chapter.name ?: ""
    }

    private fun fastForward() {
        val currentPosition = playerView.player?.currentPosition ?: 0
        val fastForwardPosition = currentPosition + appPreferences.getValue(appPreferences.playerSeekForwardInc)
        seekTo(fastForwardPosition)
        animateRipple(activity.binding.imageFfwdAnimationRipple)
    }

    private fun rewind() {
        val currentPosition = playerView.player?.currentPosition ?: 0
        val rewindPosition = currentPosition - appPreferences.getValue(appPreferences.playerSeekBackInc)
        seekTo(rewindPosition.coerceAtLeast(0))
        animateRipple(activity.binding.imageRewindAnimationRipple)
    }

    private fun togglePlayback() {
        playerView.player?.playWhenReady = !playerView.player?.playWhenReady!!
        animateRipple(activity.binding.imagePlaybackAnimationRipple)
    }

    private fun seekTo(position: Long) {
        playerView.player?.seekTo(position)
    }

    private fun animateRipple(image: ImageView) {
        image
            .animateSeekingRippleStart()
            .withEndAction {
                resetRippleImage(image)
            }
            .start()
    }

    private fun ImageView.animateSeekingRippleStart(): ViewPropertyAnimator {
        val rippleImageHeight = this.height
        val playerViewHeight = playerView.height.toFloat()
        val playerViewWidth = playerView.width.toFloat()
        val scaleDifference = playerViewHeight / rippleImageHeight
        val playerViewAspectRatio = playerViewWidth / playerViewHeight
        val scaleValue = scaleDifference * playerViewAspectRatio
        return animate()
            .alpha(1f)
            .scaleX(scaleValue)
            .scaleY(scaleValue)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
    }

    private fun resetRippleImage(image: ImageView) {
        image
            .animateSeekingRippleEnd()
            .withEndAction {
                image.scaleX = 1f
                image.scaleY = 1f
            }
            .start()
    }

    private fun ImageView.animateSeekingRippleEnd() = animate()
        .alpha(0f)
        .setDuration(150)
        .setInterpolator(AccelerateInterpolator())

    private val seekGestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            @SuppressLint("SetTextI18n")
            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (firstEvent == null) return false
                // Excludes area where app gestures conflicting with system gestures
                if (inExclusionArea(firstEvent)) return false
                // Disables seek gestures if view is locked
                if (isControlsLocked) return false

                // Check whether swipe was oriented vertically
                if (abs(distanceY / distanceX) < 2) {
                    return if ((abs(currentEvent.x - firstEvent.x) > 50 || swipeGestureProgressOpen) &&
                        !swipeGestureBrightnessOpen &&
                        !swipeGestureVolumeOpen &&
                        (SystemClock.elapsedRealtime() - lastScaleEvent) > 200
                    ) {
                        val currentPos = playerView.player?.currentPosition ?: 0
                        val vidDuration = (playerView.player?.duration ?: 0).coerceAtLeast(0)

                        val difference = ((currentEvent.x - firstEvent.x) * 90).toLong()
                        val newPos = (currentPos + difference).coerceIn(0, vidDuration)

                        activity.binding.progressScrubberLayout.visibility = View.VISIBLE
                        activity.binding.progressScrubberText.text = "${longToTimestamp(difference)} [${longToTimestamp(newPos, true)}]"
                        swipeGestureValueTrackerProgress = newPos

                        if (appPreferences.getValue(appPreferences.playerGesturesSeekTrickplay)) {
                            if (currentTrickplay != null) {
                                activity.binding.progressScrubberTrickplay.visibility = View.VISIBLE
                                updateTrickplayImage(newPos)
                            } else {
                                activity.binding.progressScrubberTrickplay.visibility = View.GONE
                            }
                        }

                        swipeGestureProgressOpen = true
                        true
                    } else {
                        false
                    }
                }
                return true
            }
        },
    )

    private val vbGestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            @SuppressLint("SetTextI18n")
            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (firstEvent == null) return false
                // Excludes area where app gestures conflicting with system gestures
                if (inExclusionArea(firstEvent)) return false
                // Disables volume gestures when player is locked
                if (isControlsLocked) return false

                if (abs(distanceY / distanceX) < 2) return false

                if (swipeGestureValueTrackerProgress > -1 || swipeGestureProgressOpen) {
                    return false
                }

                val viewCenterX = playerView.measuredWidth / 2

                // Distance to swipe to go from min to max
                val distanceFull = playerView.measuredHeight * Constants.FULL_SWIPE_RANGE_SCREEN_RATIO
                val ratioChange = distanceY / distanceFull

                if (firstEvent.x.toInt() > viewCenterX) {
                    // Swiping on the right, change volume

                    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (swipeGestureValueTrackerVolume == -1f) swipeGestureValueTrackerVolume = currentVolume.toFloat()

                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val change = ratioChange * maxVolume
                    swipeGestureValueTrackerVolume = (swipeGestureValueTrackerVolume + change).coerceIn(0f, maxVolume.toFloat())

                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, swipeGestureValueTrackerVolume.toInt(), 0)

                    activity.binding.gestureVolumeLayout.visibility = View.VISIBLE
                    activity.binding.gestureVolumeProgressBar.max = maxVolume.times(100)
                    activity.binding.gestureVolumeProgressBar.progress = swipeGestureValueTrackerVolume.times(100).toInt()
                    val process = (swipeGestureValueTrackerVolume / maxVolume.toFloat()).times(100).toInt()
                    activity.binding.gestureVolumeText.text = "$process%"
                    activity.binding.gestureVolumeImage.setImageLevel(process)

                    swipeGestureVolumeOpen = true
                } else {
                    // Swiping on the left, change brightness
                    val window = activity.window
                    val brightnessRange = BRIGHTNESS_OVERRIDE_OFF..BRIGHTNESS_OVERRIDE_FULL

                    // Initialize on first swipe
                    if (swipeGestureValueTrackerBrightness == -1f) {
                        val brightness = window.attributes.screenBrightness
                        Timber.d("Brightness ${Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)}")
                        swipeGestureValueTrackerBrightness = when (brightness) {
                            in brightnessRange -> brightness
                            else -> Settings.System.getFloat(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255
                        }
                    }
                    swipeGestureValueTrackerBrightness = (swipeGestureValueTrackerBrightness + ratioChange).coerceIn(brightnessRange)
                    val lp = window.attributes
                    lp.screenBrightness = swipeGestureValueTrackerBrightness
                    window.attributes = lp

                    activity.binding.gestureBrightnessLayout.visibility = View.VISIBLE
                    activity.binding.gestureBrightnessProgressBar.max = BRIGHTNESS_OVERRIDE_FULL.times(100).toInt()
                    activity.binding.gestureBrightnessProgressBar.progress = lp.screenBrightness.times(100).toInt()
                    val process = (lp.screenBrightness / BRIGHTNESS_OVERRIDE_FULL).times(100).toInt()
                    activity.binding.gestureBrightnessText.text = "$process%"
                    activity.binding.gestureBrightnessImage.setImageLevel(process)

                    swipeGestureBrightnessOpen = true
                }
                return true
            }
        },
    )

    private val hideGestureVolumeIndicatorOverlayAction = Runnable {
        activity.binding.gestureVolumeLayout.visibility = View.GONE
    }

    private val hideGestureBrightnessIndicatorOverlayAction = Runnable {
        activity.binding.gestureBrightnessLayout.visibility = View.GONE
        if (appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember)) {
            appPreferences.setValue(appPreferences.playerBrightness, activity.window.attributes.screenBrightness)
        }
    }

    private val hideGestureProgressOverlayAction = Runnable {
        activity.binding.progressScrubberLayout.visibility = View.GONE
    }

    /**
     * Handles scale/zoom gesture
     */
    private val zoomGestureDetector = ScaleGestureDetector(
        playerView.context,
        object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Disables zoom gesture if view is locked
                if (isControlsLocked) return false
                lastScaleEvent = SystemClock.elapsedRealtime()
                val scaleFactor = detector.scaleFactor
                if (abs(scaleFactor - Constants.ZOOM_SCALE_BASE) > Constants.ZOOM_SCALE_THRESHOLD) {
                    val enableZoom = scaleFactor > 1
                    updateZoomMode(enableZoom)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
        },
    ).apply { isQuickScaleEnabled = false }

    fun updateZoomMode(enabled: Boolean) {
        if (playerView.player is MPVPlayer) {
            (playerView.player as MPVPlayer).updateZoomMode(enabled)
        } else {
            playerView.resizeMode = if (enabled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        isZoomEnabled = enabled
    }

    private fun releaseAction(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            activity.binding.gestureVolumeLayout.apply {
                if (visibility == View.VISIBLE) {
                    removeCallbacks(hideGestureVolumeIndicatorOverlayAction)
                    postDelayed(hideGestureVolumeIndicatorOverlayAction, 1000)
                    swipeGestureVolumeOpen = false
                }
            }
            activity.binding.gestureBrightnessLayout.apply {
                if (visibility == View.VISIBLE) {
                    removeCallbacks(hideGestureBrightnessIndicatorOverlayAction)
                    postDelayed(hideGestureBrightnessIndicatorOverlayAction, 1000)
                    swipeGestureBrightnessOpen = false
                }
            }
            activity.binding.progressScrubberLayout.apply {
                if (visibility == View.VISIBLE) {
                    if (swipeGestureValueTrackerProgress > -1) {
                        playerView.player?.seekTo(swipeGestureValueTrackerProgress)
                    }
                    removeCallbacks(hideGestureProgressOverlayAction)
                    postDelayed(hideGestureProgressOverlayAction, 1000)
                    swipeGestureProgressOpen = false

                    swipeGestureValueTrackerProgress = -1L
                }
            }
            currentNumberOfPointers = 0
        }
        if (lastPlaybackSpeed > 0 && (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL)) {
            playerView.player?.setPlaybackSpeed(lastPlaybackSpeed)
            lastPlaybackSpeed = 0f
            activity.binding.gestureSpeedLayout.visibility = View.GONE
        }
    }

    private fun longToTimestamp(duration: Long, noSign: Boolean = false): String {
        val sign = if (noSign) "" else if (duration < 0) "-" else "+"
        val seconds = abs(duration).div(1000)

        return String.format("%s%02d:%02d:%02d", sign, seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    /**
     * Check if [firstEvent] is in the gesture exclusion area
     */
    private fun inExclusionArea(firstEvent: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = playerView.rootWindowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemGestures())

            if ((firstEvent.x < insets.left) || (firstEvent.x > (screenWidth - insets.right)) ||
                (firstEvent.y < insets.top) || (firstEvent.y > (screenHeight - insets.bottom))
            ) {
                return true
            }
        } else if (firstEvent.y < playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_VERTICAL) ||
            firstEvent.y > screenHeight - playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_VERTICAL) ||
            firstEvent.x < playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_HORIZONTAL) ||
            firstEvent.x > screenWidth - playerView.resources.dip(Constants.GESTURE_EXCLUSION_AREA_HORIZONTAL)
        ) {
            return true
        }
        return false
    }

    fun updateTrickplayImage(position: Long) {
        try {
            val trickplay = currentTrickplay ?: return
            val bitmap = trickplay.images[position.div(trickplay.interval).toInt()]

            if (currentTrickplayBitmap != bitmap) {
                activity.binding.progressScrubberTrickplay.load(bitmap) {
                    coroutineContext(Dispatchers.Main.immediate)
                    crossfade(false)
                    transformations(trickplayRoundedCorners)
                }
                currentTrickplayBitmap = bitmap
            }
        } catch (e: Exception) {
            activity.binding.progressScrubberTrickplay.visibility = View.GONE
            Timber.d(e)
        }
    }

    init {
        if (appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember)) {
            activity.window.attributes.screenBrightness = appPreferences.getValue(appPreferences.playerBrightness)
        }

        updateZoomMode(appPreferences.getValue(appPreferences.playerGesturesStartMaximized))

        @Suppress("ClickableViewAccessibility")
        playerView.setOnTouchListener { _, event ->
            if (playerView.useController) {
                currentNumberOfPointers = event.pointerCount
                when (event.pointerCount) {
                    1 -> {
                        tapGestureDetector.onTouchEvent(event)
                        if (appPreferences.getValue(appPreferences.playerGesturesVB)) vbGestureDetector.onTouchEvent(event)
                        if (appPreferences.getValue(appPreferences.playerGesturesSeek)) seekGestureDetector.onTouchEvent(event)
                    }
                    2 -> {
                        if (appPreferences.getValue(appPreferences.playerGesturesZoom)) zoomGestureDetector.onTouchEvent(event)
                    }
                }
            }
            releaseAction(event)
            true
        }
    }
}
