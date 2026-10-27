package com.flagship.videoplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.flagship.videoplayer.data.*
import com.flagship.videoplayer.engine.*
import com.flagship.videoplayer.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class SideSheetTab(val title: String) {
    SUBTITLES("Subtitles & sync"),
    AUDIO("Audio & delay"),
    BOOKMARKS_LOOPS("Bookmarks & saved loops"),
    SPEED("Playback speed"),
    CUSTOMIZE_CONTROLS("Customize HUD controls")
}

enum class ActiveGestureHud {
    NONE,
    BRIGHTNESS,
    VOLUME,
    HOLD_FAST_FORWARD,
    SWIPE_SEEK,
    FINE_SCRUBBING
}

@Composable
fun FlagshipPlayerScreen(
    viewModel: PlayerViewModel,
    isInPipMode: Boolean,
    onEnterPipMode: () -> Unit,
    onExitPlayer: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val localView = LocalView.current
    val configuration = LocalConfiguration.current
    val isPortraitLayout = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val tokens = LocalVdxTokens.current
    val palette = remember(tokens) { DynamicVideoAccentExtractor.buildFromThemeTokens(tokens) }

    val exoPlayer by viewModel.exoPlayerFlow.collectAsState()
    val activeVideo by viewModel.activeVideo.collectAsState()
    val hasPreviousVideo by viewModel.hasPreviousVideo.collectAsState()
    val hasNextVideo by viewModel.hasNextVideo.collectAsState()
    val prefs by viewModel.preferencesState.collectAsState()

    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val bufferedPercentage by viewModel.bufferedPercentage.collectAsState()
    val decoderMode by viewModel.decoderMode.collectAsState()
    val isCurrentVideoHdr by viewModel.isCurrentVideoHdr.collectAsState()
    val activeHdrFormatBadge by viewModel.activeHdrFormatBadge.collectAsState()
    val forceSdrToneMapping by viewModel.forceSdrToneMapping.collectAsState()
    val shouldApplySdrVisualToneMap by viewModel.shouldApplySdrVisualToneMap.collectAsState()

    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val isHoldFastForward by viewModel.isHoldFastForwardActive.collectAsState()
    val activeHoldSpeed by viewModel.activeHoldSpeed.collectAsState()

    val zoomScale by viewModel.zoomScale.collectAsState()
    val panOffsetX by viewModel.panOffsetX.collectAsState()
    val panOffsetY by viewModel.panOffsetY.collectAsState()
    val aspectRatioMode by viewModel.aspectRatioMode.collectAsState()
    val orientationMode by viewModel.orientationMode.collectAsState()

    val isTouchLocked by viewModel.isTouchLocked.collectAsState()
    val showNerdStats by viewModel.showNerdStats.collectAsState()
    val nerdStats by viewModel.nerdStats.collectAsState()

    val volumeFraction by viewModel.volumeFraction.collectAsState()
    val brightnessOverride by viewModel.brightnessOverride.collectAsState()

    val loopStartMs by viewModel.loopStartMs.collectAsState()
    val loopEndMs by viewModel.loopEndMs.collectAsState()
    val isLoopEditorVisible by viewModel.isLoopEditorVisible.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val savedLoops by viewModel.savedLoops.collectAsState()

    val audioTracks by viewModel.audioTracks.collectAsState()
    val isAudioDisabled by viewModel.isAudioDisabled.collectAsState()
    val audioDelayMs by viewModel.audioDelayMs.collectAsState()
    val subtitleTracks by viewModel.subtitleTracks.collectAsState()
    val externalSubtitleLabel by viewModel.externalSubtitleLabel.collectAsState()
    val subtitleDelayMs by viewModel.subtitleDelayMs.collectAsState()
    val subtitleSpeedFactor by viewModel.subtitleSpeedFactor.collectAsState()
    val subtitleAnchorTimeMs by viewModel.subtitleAnchorTimeMs.collectAsState()
    val subtitlesEnabled by viewModel.subtitlesEnabled.collectAsState()
    val activeSubtitleText by viewModel.activeSubtitleLines.collectAsState()

    var isOsdVisible by remember { mutableStateOf(true) }
    var isAnyDropdownMenuOpen by remember { mutableStateOf(false) }
    var activeSideSheetTab by remember { mutableStateOf<SideSheetTab?>(null) }
    var activeGestureHud by remember { mutableStateOf(ActiveGestureHud.NONE) }
    var fineScrubSensitivityLabel by remember { mutableStateOf("1.0x scrub") }
    var fineScrubPreviewTimeMs by remember { mutableStateOf(0L) }
    var swipeSeekDeltaMs by remember { mutableStateOf(0L) }
    var swipeSeekTargetMs by remember { mutableStateOf(0L) }

    var doubleTapSeekAccumulatedSec by remember { mutableIntStateOf(0) }
    var doubleTapSide by remember { mutableIntStateOf(0) }
    var lastDoubleTapTimestamp by remember { mutableLongStateOf(0L) }

    var showLockedWakeShield by remember { mutableStateOf(false) }

    // Contextual Rotation Suggestion Icon when physically turning the device
    var suggestedRotationTarget by remember { mutableStateOf<PlayerOrientationMode?>(null) }
    var lastSuggestionAtMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(context, isPortraitLayout, isInPipMode, isTouchLocked) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || isInPipMode || isTouchLocked) return
                val deviceHeldLandscape = (orientation in 60..120) || (orientation in 240..300)
                val deviceHeldPortrait = (orientation in 0..30) || (orientation in 330..360) || (orientation in 150..210)
                val target = when {
                    isPortraitLayout && deviceHeldLandscape -> PlayerOrientationMode.SENSOR_LANDSCAPE
                    !isPortraitLayout && deviceHeldPortrait -> PlayerOrientationMode.PORTRAIT
                    else -> null
                }
                if (target != null) {
                    if (suggestedRotationTarget != target) {
                        suggestedRotationTarget = target
                    }
                    lastSuggestionAtMs = System.currentTimeMillis()
                } else {
                    suggestedRotationTarget = null
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(lastSuggestionAtMs) {
        if (lastSuggestionAtMs > 0L) {
            delay(4000L)
            if (System.currentTimeMillis() - lastSuggestionAtMs >= 3900L) {
                suggestedRotationTarget = null
            }
        }
    }

    var activeBannerText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.toastBanner.collect { msg ->
            activeBannerText = msg
            delay(1700L)
            if (activeBannerText == msg) activeBannerText = null
        }
    }

    LaunchedEffect(
        isOsdVisible,
        isPlaying,
        activeSideSheetTab,
        activeGestureHud,
        isAnyDropdownMenuOpen,
        isLoopEditorVisible
    ) {
        if (isOsdVisible &&
            isPlaying &&
            activeSideSheetTab == null &&
            !isAnyDropdownMenuOpen &&
            !isLoopEditorVisible &&
            activeGestureHud == ActiveGestureHud.NONE
        ) {
            delay(3800L)
            if (!isAnyDropdownMenuOpen && activeSideSheetTab == null && !isLoopEditorVisible) {
                isOsdVisible = false
            }
        }
    }

    LaunchedEffect(lastDoubleTapTimestamp) {
        if (lastDoubleTapTimestamp > 0L) {
            delay(650L)
            doubleTapSeekAccumulatedSec = 0
            doubleTapSide = 0
        }
    }

    LaunchedEffect(isCurrentVideoHdr, shouldApplySdrVisualToneMap, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = if (isCurrentVideoHdr && !shouldApplySdrVisualToneMap) {
                ActivityInfo.COLOR_MODE_HDR
            } else {
                ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }

    // Exact v4.0 Window & Immersive Setup (No dynamic nav-bar toggling)
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insetsController = if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView)
        } else null

        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = window?.attributes
            if (lp != null) {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && window != null) {
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Release brightness override in Picture-in-Picture mode so the phone's brightness slider works!
    LaunchedEffect(brightnessOverride, isInPipMode, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        val lp = window.attributes
        val targetOverride = brightnessOverride
        lp.screenBrightness = if (isInPipMode || targetOverride == null) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            targetOverride.coerceIn(0.01f, 1.0f)
        }
        window.attributes = lp
    }

    LaunchedEffect(orientationMode, activeVideo, activity) {
        val act = activity ?: return@LaunchedEffect
        act.requestedOrientation = when (orientationMode) {
            PlayerOrientationMode.SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayerOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            PlayerOrientationMode.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            PlayerOrientationMode.AUTO_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            PlayerOrientationMode.MATCH_VIDEO -> {
                val vid = activeVideo
                if (vid != null && vid.height > vid.width) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
    }

    val subtitleFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "External_Subtitles.srt"
            viewModel.loadExternalSubtitleFile(uri, name, restoreEnable = true)
        }
    }

    val audioFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.loadExternalAudioFile(uri)
        }
    }

    BackHandler {
        when {
            activeSideSheetTab != null -> activeSideSheetTab = null
            isLoopEditorVisible -> viewModel.setLoopEditorVisible(false)
            isTouchLocked -> showLockedWakeShield = true
            else -> onExitPlayer()
        }
    }

    val viewportWidthFraction by animateFloatAsState(
        targetValue = if (activeSideSheetTab != null && !isInPipMode) 0.70f else 1.0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "ViewportWidthFraction"
    )

    // High clearance in Portrait when OSD is visible so subtitles never collide with bottom transport buttons
    val osdBottomClearance = if (isPortraitLayout) 168.dp else 88.dp
    val animatedSubtitleBottomMargin by animateDpAsState(
        targetValue = when {
            isInPipMode -> 4.dp
            isOsdVisible && activeSideSheetTab == null && !isTouchLocked ->
                maxOf(prefs.subtitleBottomMarginDp.dp, osdBottomClearance)
            else -> prefs.subtitleBottomMarginDp.dp
        },
        animationSpec = tween(durationMillis = 140),
        label = "SubtitleBottomMargin"
    )

    val effectiveBri = brightnessOverride ?: viewModel.readCurrentSystemBrightnessFraction()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(viewportWidthFraction)
                .align(Alignment.CenterStart)
                .clipToBounds()
        ) {
            VideoSurfaceLayer(
                exoPlayer = exoPlayer,
                aspectRatioMode = aspectRatioMode,
                zoomScale = zoomScale,
                panOffsetX = panOffsetX,
                panOffsetY = panOffsetY,
                applySdrToneMapFilter = shouldApplySdrVisualToneMap
            )

            if (effectiveBri < 0f && !isInPipMode) {
                val veilAlpha = (-effectiveBri).coerceIn(0f, 0.75f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = veilAlpha))
                )
            }

            if (subtitlesEnabled && !activeSubtitleText.isNullOrBlank()) {
                CustomSubtitleCanvasLayer(
                    joinedText = activeSubtitleText!!,
                    prefs = prefs,
                    bottomMargin = animatedSubtitleBottomMargin,
                    isInPipMode = isInPipMode,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // Picture-in-Picture Mode: Double-tap left/right to skip backward/forward!
        if (isInPipMode) {
            val latestPipSkipSec by rememberUpdatedState(prefs.skipIntervalSeconds)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val isForward = offset.x >= w * 0.5f
                                val signedSec = if (isForward) latestPipSkipSec else -latestPipSkipSec
                                doubleTapSide = if (isForward) 1 else -1
                                doubleTapSeekAccumulatedSec += signedSec
                                lastDoubleTapTimestamp = System.currentTimeMillis()
                                viewModel.seekRelativeMs(signedSec * 1000L)
                            }
                        )
                    }
            ) {
                if (doubleTapSide != 0 && doubleTapAccumulatedSec != 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Black.copy(alpha = 0.70f),
                        modifier = Modifier
                            .align(if (doubleTapSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(horizontal = 10.dp)
                    ) {
                        Text(
                            text = "${if (doubleTapSide > 0) "+" else "-"}${abs(doubleTapAccumulatedSec)}s",
                            style = TabularMonoStyle,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            return@Box
        }

        AnimatedVisibility(
            visible = isOsdVisible && !isTouchLocked && activeGestureHud == ActiveGestureHud.NONE,
            enter = fadeIn(tween(110)),
            exit = fadeOut(tween(110))
        ) {
            VignetteScrimLayer()
        }

        if (!isTouchLocked) {
            GestureInterceptorLayer(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(viewportWidthFraction)
                    .align(Alignment.CenterStart),
                doubleTapSeekSeconds = prefs.skipIntervalSeconds,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                currentBrightness = effectiveBri,
                currentVolume = volumeFraction,
                currentZoom = zoomScale,
                currentPanX = panOffsetX,
                currentPanY = panOffsetY,
                defaultHoldSpeed = prefs.holdFastForwardSpeed,
                onSingleTap = {
                    when {
                        activeSideSheetTab != null -> activeSideSheetTab = null
                        isLoopEditorVisible -> {
                            viewModel.setLoopEditorVisible(false)
                            isOsdVisible = false
                        }
                        else -> isOsdVisible = !isOsdVisible
                    }
                },
                onCenterDoubleTap = {
                    localView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.togglePlayPause()
                },
                onSideDoubleTapSeek = { isForward, seekSec ->
                    localView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val signedSec = if (isForward) seekSec else -seekSec
                    doubleTapSide = if (isForward) 1 else -1
                    doubleTapSeekAccumulatedSec += signedSec
                    lastDoubleTapTimestamp = System.currentTimeMillis()
                    viewModel.seekRelativeMs(signedSec * 1000L)
                },
                onHorizontalSwipeSeekStart = {
                    viewModel.beginIncrementalSeekSession()
                },
                onHorizontalSwipeSeekMove = { targetMs, deltaMs ->
                    activeGestureHud = ActiveGestureHud.SWIPE_SEEK
                    isOsdVisible = false
                    swipeSeekTargetMs = targetMs
                    swipeSeekDeltaMs = deltaMs
                    viewModel.updateIncrementalSeekSession(targetMs)
                },
                onHorizontalSwipeSeekEnd = { targetMs ->
                    viewModel.endIncrementalSeekSession(targetMs, exactFrame = false)
                    activeGestureHud = ActiveGestureHud.NONE
                },
                onBrightnessGesture = { newBrightness ->
                    activeGestureHud = ActiveGestureHud.BRIGHTNESS
                    isOsdVisible = false
                    viewModel.setContinuousBrightnessFraction(newBrightness)
                },
                onVolumeGesture = { newVolume, crossed100Percent ->
                    activeGestureHud = ActiveGestureHud.VOLUME
                    isOsdVisible = false
                    if (crossed100Percent) {
                        localView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    viewModel.setContinuousVolumeFraction(newVolume)
                },
                onHoldFastForwardStart = { initialSpeed ->
                    localView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    activeGestureHud = ActiveGestureHud.HOLD_FAST_FORWARD
                    isOsdVisible = false
                    viewModel.startHoldFastForward(initialSpeed)
                },
                onHoldFastForwardSlide = { newMultiplier, steppedTick ->
                    if (steppedTick) {
                        localView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    viewModel.updateHoldFastForwardSpeed(newMultiplier)
                },
                onHoldFastForwardEnd = {
                    viewModel.stopHoldFastForward()
                    activeGestureHud = ActiveGestureHud.NONE
                },
                onPinchZoomAndPan = { newScale, newPanX, newPanY ->
                    viewModel.updateZoomAndPan(newScale, newPanX, newPanY)
                },
                onGestureEnded = {
                    activeGestureHud = ActiveGestureHud.NONE
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showLockedWakeShield = !showLockedWakeShield
                    }
            )
        }

        TransientGestureHudLayer(
            activeGestureHud = activeGestureHud,
            brightnessFraction = effectiveBri,
            volumeFraction = volumeFraction,
            isHoldFastForward = isHoldFastForward,
            activeHoldSpeed = activeHoldSpeed,
            swipeSeekTargetMs = swipeSeekTargetMs,
            swipeSeekDeltaMs = swipeSeekDeltaMs,
            doubleTapSide = doubleTapSide,
            doubleTapAccumulatedSec = doubleTapSeekAccumulatedSec,
            zoomScale = zoomScale,
            fineScrubTimeMs = fineScrubPreviewTimeMs,
            fineScrubSensitivityLabel = fineScrubSensitivityLabel,
            showNerdStats = showNerdStats,
            nerdStats = nerdStats,
            toastBannerText = activeBannerText,
            palette = palette,
            onResetZoom = { viewModel.resetZoomAndPan() },
            onCloseNerdStats = { viewModel.toggleNerdStats() }
        )

        AnimatedVisibility(
            visible = isOsdVisible && !isTouchLocked && activeGestureHud == ActiveGestureHud.NONE,
            enter = fadeIn(tween(110)),
            exit = fadeOut(tween(110))
        ) {
            MainOsdChromeLayer(
                videoTitle = activeVideo?.titleWithoutExt ?: "Video",
                isPlaying = isPlaying,
                isPortraitMode = isPortraitLayout,
                hasPreviousVideo = hasPreviousVideo,
                hasNextVideo = hasNextVideo,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                bufferedPercentage = bufferedPercentage,
                decoderMode = decoderMode,
                isCurrentVideoHdr = isCurrentVideoHdr,
                activeHdrFormatBadge = activeHdrFormatBadge,
                forceSdrToneMapping = forceSdrToneMapping,
                playbackSpeed = playbackSpeed,
                aspectRatioMode = aspectRatioMode,
                orientationMode = orientationMode,
                enabledTopLeftSlots = prefs.enabledTopLeftSlots,
                enabledTopRightSlots = prefs.enabledTopRightSlots,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
                isLoopEditorVisible = isLoopEditorVisible,
                bookmarks = bookmarks,
                savedLoops = savedLoops,
                palette = palette,
                onMenuOpenStateChanged = { isOpen -> isAnyDropdownMenuOpen = isOpen },
                onBack = onExitPlayer,
                onSelectDecoder = { mode ->
                    localView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.selectDecoderMode(mode)
                },
                onToggleForceSdrToneMapping = {
                    viewModel.toggleForceSdrToneMapping()
                },
                onEnterPip = onEnterPipMode,
                onToggleNerdStats = { viewModel.toggleNerdStats() },
                onPlayPreviousVideo = {
                    localView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.playPreviousVideoInQueue()
                },
                onPlayPause = {
                    localView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.togglePlayPause()
                },
                onPlayNextVideo = {
                    localView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.playNextVideoInQueue()
                },
                onTimelineScrubStart = {
                    viewModel.beginIncrementalSeekSession()
                },
                onTimelineScrubMove = { targetMs, sensitivityLabel ->
                    activeGestureHud = ActiveGestureHud.FINE_SCRUBBING
                    fineScrubPreviewTimeMs = targetMs
                    fineScrubSensitivityLabel = sensitivityLabel
                    viewModel.updateIncrementalSeekSession(targetMs)
                },
                onTimelineScrubEnd = { targetMs ->
                    activeGestureHud = ActiveGestureHud.NONE
                    viewModel.endIncrementalSeekSession(targetMs, exactFrame = false)
                },
                onTapBookmarkDiamond = { bm ->
                    localView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    viewModel.jumpToBookmarkAndPlay(bm)
                },
                onDragLoopBracket = { newStartA, newEndB ->
                    viewModel.setLoopPointsDirect(newStartA, newEndB)
                },
                onTapLoopButton = {
                    localView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    viewModel.toggleLoopEditorOrStartLoop()
                },
                onSetLoopPointANow = {
                    localView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    viewModel.setLoopPointANow()
                },
                onSetLoopPointBNow = {
                    localView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    viewModel.setLoopPointBNow()
                },
                onHideLoopEditor = {
                    viewModel.setLoopEditorVisible(false)
                    isOsdVisible = false
                },
                onAdjustLoopA = { deltaMs ->
                    localView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    viewModel.adjustLoopPointA(deltaMs)
                },
                onAdjustLoopB = { deltaMs ->
                    localView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    viewModel.adjustLoopPointB(deltaMs)
                },
                onSaveLoop = {
                    localView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    viewModel.saveCurrentAbLoop()
                },
                onClearLoop = { viewModel.clearAbLoop() },
                onAddBookmark = {
                    localView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    viewModel.addBookmarkAtCurrentPosition()
                },
                onLockTouch = {
                    localView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.setTouchLocked(true)
                    showLockedWakeShield = true
                },
                onOpenSideSheet = { tab ->
                    activeSideSheetTab = if (activeSideSheetTab == tab) null else tab
                },
                onCycleAspectRatio = { viewModel.cycleAspectRatioMode() },
                onTogglePortraitLandscape = { viewModel.togglePortraitLandscape() }
            )
        }

        // Contextual Corner Rotation Suggestion Button (Appears briefly when phone is rotated)
        AnimatedVisibility(
            visible = suggestedRotationTarget != null && !isTouchLocked && activeSideSheetTab == null,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 20.dp,
                    bottom = if (isOsdVisible) (if (isPortraitLayout) 156.dp else 92.dp) else 24.dp
                )
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, palette.primaryAccent.copy(alpha = 0.65f)),
                onClick = {
                    suggestedRotationTarget?.let { target ->
                        localView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        viewModel.setOrientationDirectly(target)
                        suggestedRotationTarget = null
                    }
                },
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.ScreenRotation,
                        contentDescription = "Rotate screen",
                        tint = palette.primaryAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isTouchLocked && showLockedWakeShield,
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(80)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.68f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                onClick = {
                    viewModel.setTouchLocked(false)
                    showLockedWakeShield = false
                    isOsdVisible = true
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Tap to unlock",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = activeSideSheetTab != null && !isTouchLocked,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(150)) + fadeIn(tween(110)),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(150)) + fadeOut(tween(110)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            activeSideSheetTab?.let { currentTab ->
                ViewportShrinkingSideSheet(
                    activeTab = currentTab,
                    onSelectTab = { activeSideSheetTab = it },
                    onClose = { activeSideSheetTab = null },
                    palette = palette,
                    prefs = prefs,
                    subtitleTracks = subtitleTracks,
                    subtitlesEnabled = subtitlesEnabled,
                    externalSubtitleLabel = externalSubtitleLabel,
                    subtitleDelayMs = subtitleDelayMs,
                    subtitleSpeedFactor = subtitleSpeedFactor,
                    subtitleAnchorTimeMs = subtitleAnchorTimeMs,
                    audioTracks = audioTracks,
                    isAudioDisabled = isAudioDisabled,
                    audioDelayMs = audioDelayMs,
                    volumeFraction = volumeFraction,
                    bookmarks = bookmarks,
                    savedLoops = savedLoops,
                    loopStartMs = loopStartMs,
                    loopEndMs = loopEndMs,
                    playbackSpeed = playbackSpeed,
                    onSelectSubtitleTrack = { viewModel.selectSubtitleTrack(it) },
                    onEnableExternalSubtitle = { viewModel.enableExternalSubtitleAgain() },
                    onPickExternalSubtitle = { subtitleFilePicker.launch(arrayOf("*/*")) },
                    onAdjustSubtitleDelay = { viewModel.adjustSubtitleDelayMs(it) },
                    onSetSubtitleDelayDirect = { viewModel.setSubtitleDelayDirectMs(it) },
                    onResetSubtitleDelay = { viewModel.resetSubtitleDelay() },
                    onSetSubtitleSpeed = { viewModel.setSubtitleSpeedFactor(it) },
                    onAnchorSubtitleSpeedHere = { viewModel.anchorSubtitleSpeedAtCurrentTime() },
                    onResetSubtitleAnchor = { viewModel.resetSubtitleSpeedAnchor() },
                    onUpdateSubtitleStyle = { font, size, color, bg, shadowS, outlineW, bold, margin ->
                        viewModel.updateSubtitleStyle(font, size, color, bg, shadowS, outlineW, bold, margin)
                    },
                    onSelectAudioTrack = { viewModel.selectAudioTrack(it) },
                    onPickExternalAudio = { audioFilePicker.launch(arrayOf("audio/*", "*/*")) },
                    onAdjustAudioDelay = { viewModel.adjustAudioDelayMs(it) },
                    onResetAudioDelay = { viewModel.resetAudioDelay() },
                    onSetVolumeBoost = { viewModel.setContinuousVolumeFraction(it) },
                    onSeekToExact = { viewModel.seekToFastOrExact(it, exactFrame = true, autoPlay = true) },
                    onAddBookmarkNow = { viewModel.addBookmarkAtCurrentPosition(it) },
                    onUpdateBookmarkLabel = { bm, lbl -> viewModel.updateBookmarkLabel(bm, lbl) },
                    onDeleteBookmark = { viewModel.deleteBookmark(it) },
                    onActivateSavedLoop = {
                        viewModel.activateSavedLoop(it)
                        activeSideSheetTab = null
                    },
                    onClearActiveLoop = { viewModel.clearAbLoop() },
                    onDeleteSavedLoop = { viewModel.deleteSavedLoop(it) },
                    onSetPlaybackSpeed = { viewModel.setNormalPlaybackSpeed(it) },
                    onToggleTopLeftSlot = { viewModel.toggleTopLeftQuickSlot(it) },
                    onToggleTopRightSlot = { viewModel.toggleTopRightQuickSlot(it) }
                )
            }
        }
    }
}

@Composable
private fun VideoSurfaceLayer(
    exoPlayer: ExoPlayer?,
    aspectRatioMode: AspectRatioMode,
    zoomScale: Float,
    panOffsetX: Float,
    panOffsetY: Float,
    applySdrToneMapFilter: Boolean
) {
    val aspectModifier = when (aspectRatioMode) {
        AspectRatioMode.RATIO_16_9 -> Modifier.aspectRatio(16f / 9f)
        AspectRatioMode.RATIO_4_3 -> Modifier.aspectRatio(4f / 3f)
        else -> Modifier.fillMaxSize()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (exoPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        subtitleView?.visibility = View.GONE
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { playerView ->
                    if (playerView.player != exoPlayer) {
                        playerView.player = exoPlayer
                    }
                    playerView.resizeMode = when (aspectRatioMode) {
                        AspectRatioMode.FIT, AspectRatioMode.RATIO_16_9, AspectRatioMode.RATIO_4_3 ->
                            AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectRatioMode.FILL ->
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioMode.STRETCH ->
                            AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }

                    if (applySdrToneMapFilter) {
                        val contrast = 1.18f
                        val translate = (-0.5f * contrast + 0.5f) * 255f
                        val contrastMatrix = ColorMatrix(
                            floatArrayOf(
                                contrast, 0f, 0f, 0f, translate,
                                0f, contrast, 0f, 0f, translate,
                                0f, 0f, contrast, 0f, translate,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        val saturationMatrix = ColorMatrix().apply { setSaturation(1.24f) }
                        contrastMatrix.postConcat(saturationMatrix)
                        val paint = Paint().apply {
                            colorFilter = ColorMatrixColorFilter(contrastMatrix)
                        }
                        playerView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                    } else {
                        playerView.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                },
                modifier = aspectModifier.graphicsLayer {
                    scaleX = zoomScale
                    scaleY = zoomScale
                    translationX = panOffsetX
                    translationY = panOffsetY
                }
            )
        }
    }
}

@Composable
private fun CustomSubtitleCanvasLayer(
    joinedText: String,
    prefs: AppPreferencesState,
    bottomMargin: Dp,
    isInPipMode: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val fontFamily = remember(prefs.subtitleFont) { resolveSubtitleFontFamily(prefs.subtitleFont) }
    val effectiveFontSize = if (isInPipMode) 9.sp else prefs.subtitleFontSizeSp.sp
    val effectiveLineHeight = if (isInPipMode) 11.sp else (prefs.subtitleFontSizeSp * 1.25f).sp
    val fontWeight = if (prefs.subtitleBold) FontWeight.Bold else FontWeight.Medium
    val textColor = Color(prefs.subtitleTextColorArgb)
    val horizPad = if (isInPipMode) 6.dp else 28.dp
    val boxHPad = if (isInPipMode) 6.dp else 12.dp
    val boxVPad = if (isInPipMode) 2.dp else 5.dp

    // Genuine hairline border stroke (0.0dp to 1.5dp) + Single Drop Shadow strength
    val outlineDp = if (isInPipMode) (prefs.subtitleOutlineWidth * 0.45f) else prefs.subtitleOutlineWidth
    val outlinePx = with(density) { outlineDp.dp.toPx() }
    val isDarkText = (
        android.graphics.Color.red(prefs.subtitleTextColorArgb) +
            android.graphics.Color.green(prefs.subtitleTextColorArgb) +
            android.graphics.Color.blue(prefs.subtitleTextColorArgb)
        ) < 220
    val borderColor = if (isDarkText) Color.White.copy(alpha = 0.90f) else Color.Black.copy(alpha = 0.92f)

    val shadowStrength = prefs.subtitleDropShadowStrength.coerceIn(0f, 1f)
    val shadowBlur = if (isInPipMode) 2f else (shadowStrength * 9f)
    val shadowOffsetY = if (isInPipMode) 1f else (shadowStrength * 3f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizPad)
            .padding(bottom = bottomMargin),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(if (isInPipMode) 4.dp else 8.dp))
                .background(Color.Black.copy(alpha = prefs.subtitleBgOpacity.coerceIn(0f, 0.92f)))
                .padding(horizontal = boxHPad, vertical = boxVPad),
            contentAlignment = Alignment.Center
        ) {
            if (outlineDp > 0.04f) {
                Text(
                    text = joinedText,
                    color = borderColor,
                    fontFamily = fontFamily,
                    fontSize = effectiveFontSize,
                    lineHeight = effectiveLineHeight,
                    fontWeight = fontWeight,
                    textAlign = TextAlign.Center,
                    maxLines = if (isInPipMode) 2 else 5,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle.Default.copy(
                        drawStyle = Stroke(
                            width = outlinePx,
                            join = StrokeJoin.Round
                        )
                    )
                )
            }
            Text(
                text = joinedText,
                color = textColor,
                fontFamily = fontFamily,
                fontSize = effectiveFontSize,
                lineHeight = effectiveLineHeight,
                fontWeight = fontWeight,
                textAlign = TextAlign.Center,
                maxLines = if (isInPipMode) 2 else 5,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle.Default.copy(
                    shadow = if (shadowStrength > 0.03f) {
                        Shadow(
                            color = Color.Black.copy(alpha = (shadowStrength * 0.92f).coerceIn(0f, 1f)),
                            offset = Offset(0f, shadowOffsetY),
                            blurRadius = shadowBlur.coerceAtLeast(0.5f)
                        )
                    } else null
                )
            )
        }
    }
}

@Composable
private fun VignetteScrimLayer() {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(116.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f))
                    )
                )
        )
    }
}

// Restored exact v4.0 GestureInterceptorLayer with 32dp edge exclusion on all 4 sides
@Composable
private fun GestureInterceptorLayer(
    modifier: Modifier = Modifier,
    doubleTapSeekSeconds: Int,
    currentPositionMs: Long,
    durationMs: Long,
    currentBrightness: Float,
    currentVolume: Float,
    currentZoom: Float,
    currentPanX: Float,
    currentPanY: Float,
    defaultHoldSpeed: Float,
    onSingleTap: () -> Unit,
    onCenterDoubleTap: () -> Unit,
    onSideDoubleTapSeek: (Boolean, Int) -> Unit,
    onHorizontalSwipeSeekStart: () -> Unit,
    onHorizontalSwipeSeekMove: (Long, Long) -> Unit,
    onHorizontalSwipeSeekEnd: (Long) -> Unit,
    onBrightnessGesture: (Float) -> Unit,
    onVolumeGesture: (Float, Boolean) -> Unit,
    onHoldFastForwardStart: (Float) -> Unit,
    onHoldFastForwardSlide: (Float, Boolean) -> Unit,
    onHoldFastForwardEnd: () -> Unit,
    onPinchZoomAndPan: (Float, Float, Float) -> Unit,
    onGestureEnded: () -> Unit
) {
    val density = LocalDensity.current
    val edgeExclusionPx = with(density) { 32.dp.toPx() }

    val latestPos by rememberUpdatedState(currentPositionMs)
    val latestDur by rememberUpdatedState(durationMs.coerceAtLeast(1L))
    val latestBrightness by rememberUpdatedState(currentBrightness)
    val latestVolume by rememberUpdatedState(currentVolume)
    val latestZoom by rememberUpdatedState(currentZoom)
    val latestPanX by rememberUpdatedState(currentPanX)
    val latestPanY by rememberUpdatedState(currentPanY)
    val latestHoldSpeed by rememberUpdatedState(defaultHoldSpeed)
    val latestDoubleTapSec by rememberUpdatedState(doubleTapSeekSeconds)

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onSingleTap() },
                    onDoubleTap = { offset ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        when {
                            offset.x < width * 0.33f -> onSideDoubleTapSeek(false, latestDoubleTapSec)
                            offset.x > width * 0.67f -> onSideDoubleTapSeek(true, latestDoubleTapSec)
                            else -> onCenterDoubleTap()
                        }
                    },
                    onLongPress = { startOffset ->
                        if (startOffset.x < edgeExclusionPx || startOffset.x > size.width - edgeExclusionPx) {
                            return@detectTapGestures
                        }
                        onHoldFastForwardStart(latestHoldSpeed)
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)

                    if (down.position.x < edgeExclusionPx ||
                        down.position.x > width - edgeExclusionPx ||
                        down.position.y < edgeExclusionPx ||
                        down.position.y > height - edgeExclusionPx
                    ) {
                        return@awaitEachGesture
                    }

                    var activeMode = 0
                    var accumBri = latestBrightness
                    var accumVol = latestVolume
                    val swipeStartPosMs = latestPos
                    var currentSwipeTargetMs = swipeStartPosMs
                    var lastSteppedHoldQuarter = (latestHoldSpeed * 4f).roundToInt()
                    val startTime = System.currentTimeMillis()
                    var totalDy = 0f
                    var totalDx = 0f

                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }

                        if (pressedCount >= 2) {
                            activeMode = 3
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val nextScale = (latestZoom * zoomChange).coerceIn(0.25f, 5.0f)
                            val nextPanX = if (nextScale > 1.01f) latestPanX + panChange.x else 0f
                            val nextPanY = if (nextScale > 1.01f) latestPanY + panChange.y else 0f
                            onPinchZoomAndPan(nextScale, nextPanX, nextPanY)
                            event.changes.forEach { it.consume() }
                        } else if (pressedCount == 1 && activeMode != 3) {
                            val change = event.changes.first()
                            val dx = change.positionChange().x
                            val dy = change.positionChange().y
                            totalDx += dx
                            totalDy += dy

                            val heldDuration = System.currentTimeMillis() - startTime
                            if (heldDuration > 430L && abs(totalDy) < 28f && abs(totalDx) < 28f && activeMode == 0) {
                                activeMode = 4
                            }

                            if (activeMode == 4) {
                                val deltaSpeed = (change.position.x - down.position.x) / (width * 0.28f)
                                val newSpeed = (latestHoldSpeed + deltaSpeed).coerceIn(1.25f, 3.5f)
                                val quarter = (newSpeed * 4f).roundToInt()
                                val ticked = quarter != lastSteppedHoldQuarter
                                lastSteppedHoldQuarter = quarter
                                onHoldFastForwardSlide(newSpeed, ticked)
                                change.consume()
                            } else if (activeMode == 0) {
                                if (abs(totalDx) > 24f && abs(totalDx) > abs(totalDy) * 1.4f) {
                                    activeMode = 5
                                    onHorizontalSwipeSeekStart()
                                } else if (abs(totalDy) > 22f && abs(totalDy) > abs(totalDx) * 1.3f) {
                                    if (down.position.x < width * 0.35f) {
                                        activeMode = 1
                                        accumBri = latestBrightness
                                    } else if (down.position.x > width * 0.65f) {
                                        activeMode = 2
                                        accumVol = latestVolume
                                    }
                                }
                            }

                            when (activeMode) {
                                1 -> {
                                    val deltaBri = -dy / (height * 0.68f)
                                    accumBri = (accumBri + deltaBri).coerceIn(-0.5f, 1.0f)
                                    onBrightnessGesture(accumBri)
                                    change.consume()
                                }
                                2 -> {
                                    val deltaVol = -dy / (height * 0.55f)
                                    val prevAbove = accumVol >= 1.0f
                                    accumVol = (accumVol + deltaVol).coerceIn(0.0f, 2.0f)
                                    val nowAbove = accumVol >= 1.0f
                                    onVolumeGesture(accumVol, !prevAbove && nowAbove)
                                    change.consume()
                                }
                                5 -> {
                                    val maxSwipeSpanMs = minOf(75_000L, (latestDur * 0.35f).roundToLong().coerceAtLeast(15_000L))
                                    val fractionX = (change.position.x - down.position.x) / (width * 0.80f)
                                    val deltaSeekMs = (fractionX * maxSwipeSpanMs).roundToLong()
                                    currentSwipeTargetMs = (swipeStartPosMs + deltaSeekMs).coerceIn(0L, latestDur)
                                    onHorizontalSwipeSeekMove(
                                        currentSwipeTargetMs,
                                        currentSwipeTargetMs - swipeStartPosMs
                                    )
                                    change.consume()
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (activeMode == 5) {
                        onHorizontalSwipeSeekEnd(currentSwipeTargetMs)
                    }
                    onHoldFastForwardEnd()
                    onGestureEnded()
                }
            }
    )
}

@Composable
private fun TransientGestureHudLayer(
    activeGestureHud: ActiveGestureHud,
    brightnessFraction: Float,
    volumeFraction: Float,
    isHoldFastForward: Boolean,
    activeHoldSpeed: Float,
    swipeSeekTargetMs: Long,
    swipeSeekDeltaMs: Long,
    doubleTapSide: Int,
    doubleTapAccumulatedSec: Int,
    zoomScale: Float,
    fineScrubTimeMs: Long,
    fineScrubSensitivityLabel: String,
    showNerdStats: Boolean,
    nerdStats: LiveNerdStats,
    toastBannerText: String?,
    palette: VideoDynamicPalette,
    onResetZoom: () -> Unit,
    onCloseNerdStats: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = activeGestureHud == ActiveGestureHud.BRIGHTNESS,
            enter = fadeIn(tween(60)),
            exit = fadeOut(tween(80)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 36.dp)
        ) {
            val pct = (brightnessFraction * 100f).roundToInt()
            val normalizedFill = ((brightnessFraction + 0.5f) / 1.5f).coerceIn(0f, 1f)
            val barColor = if (brightnessFraction < 0f) palette.tertiaryLoopAccent else palette.primaryAccent

            VerticalGlassGesturePill(
                icon = if (brightnessFraction < 0f) Icons.Default.NightlightRound else Icons.Default.WbSunny,
                percentageText = "$pct%",
                fillFraction = normalizedFill,
                fillColor = barColor,
                palette = palette
            )
        }

        AnimatedVisibility(
            visible = activeGestureHud == ActiveGestureHud.VOLUME,
            enter = fadeIn(tween(60)),
            exit = fadeOut(tween(80)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 36.dp)
        ) {
            val pct = (volumeFraction * 100f).roundToInt()
            val normalizedFill = (volumeFraction / 2.0f).coerceIn(0f, 1f)
            val isBoosted = volumeFraction > 1.01f
            val barColor = if (isBoosted) palette.tertiaryLoopAccent else palette.primaryAccent
            val icon = when {
                pct == 0 -> Icons.Default.VolumeOff
                pct < 50 -> Icons.Default.VolumeDown
                else -> Icons.Default.VolumeUp
            }

            VerticalGlassGesturePill(
                icon = icon,
                percentageText = "$pct%",
                fillFraction = normalizedFill,
                fillColor = barColor,
                palette = palette
            )
        }

        AnimatedVisibility(
            visible = isHoldFastForward || activeGestureHud == ActiveGestureHud.HOLD_FAST_FORWARD,
            enter = fadeIn(tween(35)),
            exit = fadeOut(tween(35)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
        ) {
            val speedText = if (abs(activeHoldSpeed - activeHoldSpeed.roundToInt()) < 0.05f) {
                "${activeHoldSpeed.roundToInt()}x"
            } else {
                String.format(Locale.US, "%.1fx", activeHoldSpeed)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.72f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = speedText,
                        style = TabularMonoStyle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                    Icon(
                        Icons.Default.FastForward,
                        contentDescription = null,
                        tint = palette.primaryAccent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = activeGestureHud == ActiveGestureHud.SWIPE_SEEK,
            enter = fadeIn(tween(45)),
            exit = fadeOut(tween(65)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val deltaSec = swipeSeekDeltaMs / 1000L
            val sign = if (deltaSec >= 0) "+" else "-"
            val absSec = abs(deltaSec)
            val deltaFormatted = String.format(Locale.US, "%s%02d:%02d", sign, absSec / 60, absSec % 60)

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.78f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = deltaFormatted,
                        style = TabularMonoStyle,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primaryAccent
                    )
                    Text(
                        text = FormatUtils.formatDurationMs(swipeSeekTargetMs),
                        style = TabularMonoStyle,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = activeGestureHud == ActiveGestureHud.FINE_SCRUBBING,
            enter = fadeIn(tween(45)),
            exit = fadeOut(tween(65)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.78f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = FormatUtils.formatPreciseCentiseconds(fineScrubTimeMs),
                        style = TabularMonoStyle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primaryAccent
                    )
                    Text(
                        text = fineScrubSensitivityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = doubleTapSide != 0 && doubleTapAccumulatedSec != 0,
            enter = fadeIn(tween(50)) + scaleIn(initialScale = 0.92f, animationSpec = tween(70)),
            exit = fadeOut(tween(110)),
            modifier = Modifier
                .align(if (doubleTapSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 42.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.62f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (doubleTapSide < 0) {
                        Icon(
                            imageVector = Icons.Default.KeyboardDoubleArrowLeft,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = "${abs(doubleTapAccumulatedSec)}s",
                        style = TabularMonoStyle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    if (doubleTapSide > 0) {
                        Icon(
                            imageVector = Icons.Default.KeyboardDoubleArrowRight,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = abs(zoomScale - 1.0f) > 0.03f && !isHoldFastForward,
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(80)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.osdElevatedSurface,
                onClick = onResetZoom
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = null, tint = palette.primaryAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${(zoomScale * 100).roundToInt()}% • Reset",
                        style = TabularNumeralStyle,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showNerdStats,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(100)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 104.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0A0B0E).copy(alpha = 0.90f),
                border = BorderStroke(1.dp, palette.primaryAccent.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Codec & renderer stats",
                            style = TabularMonoStyle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.primaryAccent
                        )
                        IconButton(
                            onClick = onCloseNerdStats,
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    Text("Video: ${nerdStats.videoCodec} • ${nerdStats.resolutionAndFps}", style = TabularMonoStyle, fontSize = 11.sp, color = Color.White)
                    Text("Dynamic range: ${nerdStats.hdrSummary}", style = TabularMonoStyle, fontSize = 11.sp, color = Color.White)
                    Text("Decoder: ${nerdStats.activeDecoderBadge}", style = TabularMonoStyle, fontSize = 11.sp, color = Color.White)
                    Text("Audio: ${nerdStats.audioCodecAndChannels} @ ${nerdStats.audioSampleRateHz} Hz", style = TabularMonoStyle, fontSize = 11.sp, color = Color.White)
                    Text("Dropped frames: ${nerdStats.droppedFrames} • ${nerdStats.networkProtocol}", style = TabularMonoStyle, fontSize = 11.sp, color = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = toastBannerText != null,
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(80)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 108.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.osdElevatedSurface,
                border = BorderStroke(1.dp, palette.primaryAccent.copy(alpha = 0.5f))
            ) {
                Text(
                    text = toastBannerText ?: "",
                    style = TabularNumeralStyle,
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun VerticalGlassGesturePill(
    icon: ImageVector,
    percentageText: String,
    fillFraction: Float,
    fillColor: Color,
    palette: VideoDynamicPalette
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.osdElevatedSurface,
        border = BorderStroke(1.dp, palette.specularBorder),
        modifier = Modifier.size(width = 50.dp, height = 188.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = percentageText,
                style = TabularNumeralStyle,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = Color.White
            )

            Box(
                modifier = Modifier
                    .width(10.dp)
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fillFraction.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(5.dp))
                        .background(fillColor)
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fillColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}
