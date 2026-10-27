package com.flagship.videoplayer.ui.player

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flagship.videoplayer.data.*
import com.flagship.videoplayer.engine.PlayerTrackOption
import com.flagship.videoplayer.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

fun Modifier.clipToBounds(): Modifier = this.clip(RectangleShape)

@Composable
fun MainOsdChromeLayer(
    videoTitle: String,
    isPlaying: Boolean,
    isPortraitMode: Boolean,
    hasPreviousVideo: Boolean,
    hasNextVideo: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    bufferedPercentage: Int,
    decoderMode: DecoderMode,
    isCurrentVideoHdr: Boolean,
    activeHdrFormatBadge: String,
    forceSdrToneMapping: Boolean,
    playbackSpeed: Float,
    aspectRatioMode: AspectRatioMode,
    orientationMode: PlayerOrientationMode,
    enabledTopLeftSlots: List<TopLeftQuickSlot>,
    enabledTopRightSlots: List<TopRightQuickSlot>,
    loopStartMs: Long?,
    loopEndMs: Long?,
    isLoopEditorVisible: Boolean,
    bookmarks: List<BookmarkEntity>,
    savedLoops: List<SavedLoopEntity>,
    palette: VideoDynamicPalette,
    onMenuOpenStateChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSelectDecoder: (DecoderMode) -> Unit,
    onToggleForceSdrToneMapping: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleNerdStats: () -> Unit,
    onPlayPreviousVideo: () -> Unit,
    onPlayPause: () -> Unit,
    onPlayNextVideo: () -> Unit,
    onTimelineScrubStart: () -> Unit,
    onTimelineScrubMove: (Long, String) -> Unit,
    onTimelineScrubEnd: (Long) -> Unit,
    onTapBookmarkDiamond: (BookmarkEntity) -> Unit,
    onDragLoopBracket: (Long, Long) -> Unit,
    onTapLoopButton: () -> Unit,
    onSetLoopPointANow: () -> Unit,
    onSetLoopPointBNow: () -> Unit,
    onHideLoopEditor: () -> Unit,
    onAdjustLoopA: (Long) -> Unit,
    onAdjustLoopB: (Long) -> Unit,
    onSaveLoop: () -> Unit,
    onClearLoop: () -> Unit,
    onAddBookmark: () -> Unit,
    onLockTouch: () -> Unit,
    onOpenSideSheet: (SideSheetTab) -> Unit,
    onCycleAspectRatio: () -> Unit,
    onTogglePortraitLandscape: () -> Unit
) {
    var showDecoderMenu by remember { mutableStateOf(false) }
    var showTopRightMainMenu by remember { mutableStateOf(false) }

    LaunchedEffect(showDecoderMenu, showTopRightMainMenu) {
        onMenuOpenStateChanged(showDecoderMenu || showTopRightMainMenu)
    }

    // Exact v4.0 padding — zero status/nav inset padding so the title, progress bar, and dropdown menus never sink or lift
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OsdRoundedIconButton(
                    icon = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    palette = palette,
                    onClick = onBack
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = videoTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isCurrentVideoHdr) {
                        Text(
                            text = activeHdrFormatBadge,
                            style = TabularMonoStyle,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.primaryAccent
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    enabledTopRightSlots.forEach { rightSlot ->
                        when (rightSlot) {
                            TopRightQuickSlot.LOCK -> {
                                OsdRoundedIconButton(
                                    icon = Icons.Outlined.Lock,
                                    contentDescription = "Lock controls",
                                    palette = palette,
                                    onClick = onLockTouch
                                )
                            }
                            TopRightQuickSlot.DECODER -> {
                                Box {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = palette.osdElevatedSurface,
                                        border = BorderStroke(1.dp, palette.primaryAccent.copy(alpha = 0.65f)),
                                        onClick = { showDecoderMenu = true },
                                        modifier = Modifier.height(38.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = decoderMode.badge,
                                                style = TabularMonoStyle,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = palette.primaryAccent
                                            )
                                            Icon(
                                                Icons.Default.ArrowDropDown,
                                                contentDescription = "Decoder menu",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = showDecoderMenu,
                                        onDismissRequest = { showDecoderMenu = false }
                                    ) {
                                        DecoderMode.entries.forEach { mode ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(mode.badge, fontWeight = FontWeight.Bold)
                                                        Text(mode.description, fontSize = 11.sp)
                                                    }
                                                },
                                                leadingIcon = {
                                                    if (decoderMode == mode) {
                                                        Icon(
                                                            Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = palette.primaryAccent
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    showDecoderMenu = false
                                                    onSelectDecoder(mode)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            TopRightQuickSlot.PIP -> {
                                OsdRoundedIconButton(
                                    icon = Icons.Default.PictureInPictureAlt,
                                    contentDescription = "Picture in Picture",
                                    palette = palette,
                                    onClick = onEnterPip
                                )
                            }
                            TopRightQuickSlot.STATS -> {
                                OsdRoundedIconButton(
                                    icon = Icons.Outlined.Analytics,
                                    contentDescription = "Codec stats",
                                    palette = palette,
                                    onClick = onToggleNerdStats
                                )
                            }
                        }
                    }

                    Box {
                        OsdRoundedIconButton(
                            icon = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            palette = palette,
                            onClick = { showTopRightMainMenu = true }
                        )

                        DropdownMenu(
                            expanded = showTopRightMainMenu,
                            onDismissRequest = { showTopRightMainMenu = false }
                        ) {
                            if (isCurrentVideoHdr) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (forceSdrToneMapping) "Restore native HDR"
                                            else "Convert HDR to SDR"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.HdrOn,
                                            contentDescription = null,
                                            tint = palette.primaryAccent
                                        )
                                    },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        onToggleForceSdrToneMapping()
                                    }
                                )
                                HorizontalDivider()
                            }

                            if (TopRightQuickSlot.LOCK !in enabledTopRightSlots) {
                                DropdownMenuItem(
                                    text = { Text("Lock controls") },
                                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        onLockTouch()
                                    }
                                )
                            }
                            if (TopRightQuickSlot.DECODER !in enabledTopRightSlots) {
                                DropdownMenuItem(
                                    text = { Text("Decoder (${decoderMode.badge})") },
                                    leadingIcon = { Icon(Icons.Default.Memory, contentDescription = null) },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        showDecoderMenu = true
                                    }
                                )
                            }

                            if (TopLeftQuickSlot.SPEED !in enabledTopLeftSlots) {
                                DropdownMenuItem(
                                    text = { Text(String.format(Locale.US, "Playback speed (%.2fx)", playbackSpeed)) },
                                    leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        onOpenSideSheet(SideSheetTab.SPEED)
                                    }
                                )
                            }
                            if (TopLeftQuickSlot.BOOKMARK !in enabledTopLeftSlots) {
                                DropdownMenuItem(
                                    text = { Text("Pin bookmark") },
                                    leadingIcon = { Icon(Icons.Outlined.BookmarkBorder, contentDescription = null) },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        onAddBookmark()
                                    }
                                )
                            }
                            if (TopLeftQuickSlot.LOOP !in enabledTopLeftSlots) {
                                DropdownMenuItem(
                                    text = { Text("A-B loop") },
                                    leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null) },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        onTapLoopButton()
                                    }
                                )
                            }
                            if (TopLeftQuickSlot.ORIENTATION !in enabledTopLeftSlots) {
                                DropdownMenuItem(
                                    text = { Text("Toggle Portrait / Landscape") },
                                    leadingIcon = { Icon(Icons.Default.ScreenRotation, contentDescription = null) },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        onTogglePortraitLandscape()
                                    }
                                )
                            }

                            DropdownMenuItem(
                                text = { Text("Subtitles & sync") },
                                leadingIcon = { Icon(Icons.Outlined.Subtitles, contentDescription = null) },
                                onClick = {
                                    showTopRightMainMenu = false
                                    onOpenSideSheet(SideSheetTab.SUBTITLES)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Audio tracks & disable audio") },
                                leadingIcon = { Icon(Icons.Outlined.Audiotrack, contentDescription = null) },
                                onClick = {
                                    showTopRightMainMenu = false
                                    onOpenSideSheet(SideSheetTab.AUDIO)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Saved loops & bookmarks (${savedLoops.size} loops)") },
                                leadingIcon = { Icon(Icons.Outlined.Bookmarks, contentDescription = null) },
                                onClick = {
                                    showTopRightMainMenu = false
                                    onOpenSideSheet(SideSheetTab.BOOKMARKS_LOOPS)
                                }
                            )
                            if (TopLeftQuickSlot.ASPECT !in enabledTopLeftSlots) {
                                DropdownMenuItem(
                                    text = { Text("Aspect ratio (${aspectRatioMode.label})") },
                                    leadingIcon = { Icon(Icons.Default.AspectRatio, contentDescription = null) },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        onCycleAspectRatio()
                                    }
                                )
                            }
                            if (TopRightQuickSlot.PIP !in enabledTopRightSlots) {
                                DropdownMenuItem(
                                    text = { Text("Picture-in-Picture") },
                                    leadingIcon = { Icon(Icons.Default.PictureInPictureAlt, contentDescription = null) },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        onEnterPip()
                                    }
                                )
                            }
                            if (TopRightQuickSlot.STATS !in enabledTopRightSlots) {
                                DropdownMenuItem(
                                    text = { Text("Codec & renderer stats") },
                                    leadingIcon = { Icon(Icons.Outlined.Analytics, contentDescription = null) },
                                    onClick = {
                                        showTopRightMainMenu = false
                                        onToggleNerdStats()
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Customize top bar buttons") },
                                leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                                onClick = {
                                    showTopRightMainMenu = false
                                    onOpenSideSheet(SideSheetTab.CUSTOMIZE_CONTROLS)
                                }
                            )
                        }
                    }
                }
            }

            if (enabledTopLeftSlots.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp)
                ) {
                    items(enabledTopLeftSlots, key = { it.key }) { slot ->
                        when (slot) {
                            TopLeftQuickSlot.SPEED -> {
                                val speedActive = abs(playbackSpeed - 1.0f) > 0.01f
                                OsdRoundedIconButton(
                                    icon = Icons.Default.Speed,
                                    badgeText = if (speedActive) String.format(Locale.US, "%.1fx", playbackSpeed) else null,
                                    isHighlighted = speedActive,
                                    contentDescription = "Playback speed",
                                    palette = palette,
                                    onClick = { onOpenSideSheet(SideSheetTab.SPEED) }
                                )
                            }
                            TopLeftQuickSlot.BOOKMARK -> {
                                OsdRoundedIconButton(
                                    icon = Icons.Outlined.BookmarkAdd,
                                    badgeText = null,
                                    isHighlighted = false,
                                    contentDescription = "Pin bookmark",
                                    palette = palette,
                                    onClick = onAddBookmark
                                )
                            }
                            TopLeftQuickSlot.LOOP -> {
                                val loopBadge = when {
                                    loopStartMs == null -> null
                                    loopEndMs == null -> "A"
                                    else -> "A-B"
                                }
                                OsdRoundedIconButton(
                                    icon = Icons.Default.Repeat,
                                    badgeText = loopBadge,
                                    isHighlighted = loopStartMs != null,
                                    highlightColor = palette.tertiaryLoopAccent,
                                    contentDescription = "A-B loop",
                                    palette = palette,
                                    onClick = onTapLoopButton
                                )
                            }
                            TopLeftQuickSlot.ORIENTATION -> {
                                OsdRoundedIconButton(
                                    icon = Icons.Default.ScreenRotation,
                                    contentDescription = "Orientation",
                                    palette = palette,
                                    onClick = onTogglePortraitLandscape
                                )
                            }
                            TopLeftQuickSlot.SUBTITLES -> {
                                OsdRoundedIconButton(
                                    icon = Icons.Outlined.Subtitles,
                                    contentDescription = "Subtitles",
                                    palette = palette,
                                    onClick = { onOpenSideSheet(SideSheetTab.SUBTITLES) }
                                )
                            }
                            TopLeftQuickSlot.AUDIO -> {
                                OsdRoundedIconButton(
                                    icon = Icons.Outlined.Audiotrack,
                                    contentDescription = "Audio",
                                    palette = palette,
                                    onClick = { onOpenSideSheet(SideSheetTab.AUDIO) }
                                )
                            }
                            TopLeftQuickSlot.ASPECT -> {
                                OsdRoundedIconButton(
                                    icon = Icons.Default.AspectRatio,
                                    contentDescription = "Aspect ratio",
                                    palette = palette,
                                    onClick = onCycleAspectRatio
                                )
                            }
                        }
                    }
                }
            }
        }

        // Landscape Only: Center Low-Key Round Transport Controls
        if (!isPortraitMode) {
            LowKeyRoundTransportCluster(
                isPlaying = isPlaying,
                hasPreviousVideo = hasPreviousVideo,
                hasNextVideo = hasNextVideo,
                onPlayPreviousVideo = onPlayPreviousVideo,
                onPlayPause = onPlayPause,
                onPlayNextVideo = onPlayNextVideo,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Bottom Progress Deck (In Portrait, low-key circular buttons sit cleanly below the progress bar)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AnimatedVisibility(
                visible = loopStartMs != null && isLoopEditorVisible,
                enter = fadeIn(tween(110)) + slideInVertically { it / 3 },
                exit = fadeOut(tween(110)) + slideOutVertically { it / 3 }
            ) {
                SleekLoopStudioBar(
                    loopStartMs = loopStartMs ?: 0L,
                    loopEndMs = loopEndMs,
                    palette = palette,
                    onSetANow = onSetLoopPointANow,
                    onSetBNow = onSetLoopPointBNow,
                    onAdjustA = onAdjustLoopA,
                    onAdjustB = onAdjustLoopB,
                    onSaveLoop = onSaveLoop,
                    onWatchUnobstructed = onHideLoopEditor,
                    onExitLoop = onClearLoop
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = FormatUtils.formatDurationMs(currentPositionMs),
                    style = TabularMonoStyle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                val remainingMs = (durationMs - currentPositionMs).coerceAtLeast(0L)
                Text(
                    text = "-${FormatUtils.formatDurationMs(remainingMs)} / ${FormatUtils.formatDurationMs(durationMs)}",
                    style = TabularMonoStyle,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }

            FlagshipTimelineCanvas(
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                bufferedPercentage = bufferedPercentage,
                loopStartMs = loopStartMs,
                loopEndMs = loopEndMs,
                bookmarks = bookmarks,
                palette = palette,
                onScrubStart = onTimelineScrubStart,
                onScrubMove = onTimelineScrubMove,
                onScrubEnd = onTimelineScrubEnd,
                onTapBookmarkDiamond = onTapBookmarkDiamond,
                onDragLoopBracket = onDragLoopBracket
            )

            if (isPortraitMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LowKeyRoundTransportCluster(
                        isPlaying = isPlaying,
                        hasPreviousVideo = hasPreviousVideo,
                        hasNextVideo = hasNextVideo,
                        onPlayPreviousVideo = onPlayPreviousVideo,
                        onPlayPause = onPlayPause,
                        onPlayNextVideo = onPlayNextVideo
                    )
                }
            }
        }
    }
}

@Composable
private fun LowKeyRoundTransportCluster(
    isPlaying: Boolean,
    hasPreviousVideo: Boolean,
    hasNextVideo: Boolean,
    onPlayPreviousVideo: () -> Unit,
    onPlayPause: () -> Unit,
    onPlayNextVideo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.48f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            onClick = { if (hasPreviousVideo) onPlayPreviousVideo() },
            modifier = Modifier
                .size(44.dp)
                .alpha(if (hasPreviousVideo) 1f else 0.32f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous video",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.58f),
            border = BorderStroke(1.2.dp, Color.White.copy(alpha = 0.24f)),
            onClick = onPlayPause,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.48f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            onClick = { if (hasNextVideo) onPlayNextVideo() },
            modifier = Modifier
                .size(44.dp)
                .alpha(if (hasNextVideo) 1f else 0.32f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next video",
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun OsdRoundedIconButton(
    icon: ImageVector,
    contentDescription: String,
    badgeText: String? = null,
    isHighlighted: Boolean = false,
    highlightColor: Color? = null,
    palette: VideoDynamicPalette,
    onClick: () -> Unit
) {
    val activeAccent = highlightColor ?: palette.primaryAccent
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isHighlighted) activeAccent.copy(alpha = 0.24f) else palette.osdSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isHighlighted) activeAccent else palette.specularBorder
        ),
        onClick = onClick,
        modifier = Modifier.height(38.dp).defaultMinSize(minWidth = 38.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (badgeText != null) 10.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isHighlighted) activeAccent else Color.White,
                modifier = Modifier.size(19.dp)
            )
            if (badgeText != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = badgeText,
                    style = TabularMonoStyle,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isHighlighted) activeAccent else Color.White
                )
            }
        }
    }
}

@Composable
private fun SleekLoopStudioBar(
    loopStartMs: Long,
    loopEndMs: Long?,
    palette: VideoDynamicPalette,
    onSetANow: () -> Unit,
    onSetBNow: () -> Unit,
    onAdjustA: (Long) -> Unit,
    onAdjustB: (Long) -> Unit,
    onSaveLoop: () -> Unit,
    onWatchUnobstructed: () -> Unit,
    onExitLoop: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF12141A).copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LoopStudioButton(
                        text = "Set A (${FormatUtils.formatDurationMs(loopStartMs)})",
                        accentColor = palette.primaryAccent,
                        onClick = onSetANow
                    )
                    LoopStudioButton(
                        text = if (loopEndMs != null) "Set B (${FormatUtils.formatDurationMs(loopEndMs)})" else "Set B here",
                        accentColor = if (loopEndMs == null) palette.primaryAccent else palette.tertiaryLoopAccent,
                        filled = loopEndMs == null,
                        onClick = onSetBNow
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (loopEndMs != null) {
                        LoopStudioButton(
                            text = "Save",
                            accentColor = palette.primaryAccent,
                            onClick = onSaveLoop
                        )
                    }
                    LoopStudioButton(
                        text = "Hide bar",
                        accentColor = Color.White.copy(alpha = 0.85f),
                        onClick = onWatchUnobstructed
                    )
                    LoopStudioButton(
                        text = "Exit loop",
                        accentColor = Color(0xFFFF6B6B),
                        onClick = onExitLoop
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Fine tune:", style = TabularMonoStyle, fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                MicroTunerChip(text = "A -0.1s", onClick = { onAdjustA(-100L) })
                MicroTunerChip(text = "A +0.1s", onClick = { onAdjustA(100L) })
                if (loopEndMs != null) {
                    Spacer(Modifier.width(4.dp))
                    MicroTunerChip(text = "B -0.1s", onClick = { onAdjustB(-100L) })
                    MicroTunerChip(text = "B +0.1s", onClick = { onAdjustB(100L) })
                }
            }
        }
    }
}

@Composable
private fun LoopStudioButton(
    text: String,
    accentColor: Color,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (filled) accentColor else accentColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
        onClick = onClick
    ) {
        Text(
            text = text,
            style = TabularMonoStyle,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (filled) Color.Black else accentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun MicroTunerChip(
    text: String,
    highlightColor: Color = Color.White,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, highlightColor.copy(alpha = 0.25f)),
        onClick = onClick
    ) {
        Text(
            text = text,
            style = TabularMonoStyle,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = highlightColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Hold-to-Repeat Step Button:
 * Fires once on tap, and if held down for 340ms, rapidly repeats every 65ms with haptic ticks.
 */
@Composable
private fun HoldRepeatStepButton(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White,
    onStep: () -> Unit
) {
    val localView = LocalView.current
    val latestOnStep by rememberUpdatedState(onStep)
    var isPressed by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isPressed) accentColor.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
        border = BorderStroke(
            1.dp,
            if (isPressed) accentColor else accentColor.copy(alpha = 0.25f)
        ),
        modifier = modifier
            .height(36.dp)
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        localView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        latestOnStep()
                        val repeatJob = launch {
                            delay(340L)
                            while (true) {
                                localView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                latestOnStep()
                                delay(65L)
                            }
                        }
                        waitForUpOrCancellation()
                        repeatJob.cancel()
                        isPressed = false
                    }
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = TabularMonoStyle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FlagshipTimelineCanvas(
    currentPositionMs: Long,
    durationMs: Long,
    bufferedPercentage: Int,
    loopStartMs: Long?,
    loopEndMs: Long?,
    bookmarks: List<BookmarkEntity>,
    palette: VideoDynamicPalette,
    onScrubStart: () -> Unit,
    onScrubMove: (Long, String) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onTapBookmarkDiamond: (BookmarkEntity) -> Unit,
    onDragLoopBracket: (Long, Long) -> Unit
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val latestDur by rememberUpdatedState(safeDuration)
    val latestLoopA by rememberUpdatedState(loopStartMs)
    val latestLoopB by rememberUpdatedState(loopEndMs)
    val latestBookmarks by rememberUpdatedState(bookmarks)

    var localScrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val displayedPositionMs = localScrubPositionMs ?: currentPositionMs

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    val startA = latestLoopA
                    val endB = latestLoopB

                    val tappedBookmark = latestBookmarks.minByOrNull { bm ->
                        val bmX = (bm.timestampMs.toFloat() / latestDur.toFloat()) * widthPx
                        abs(down.position.x - bmX)
                    }?.takeIf { bm ->
                        val bmX = (bm.timestampMs.toFloat() / latestDur.toFloat()) * widthPx
                        abs(down.position.x - bmX) <= 26f
                    }

                    val aX = if (startA != null) (startA.toFloat() / latestDur.toFloat()) * widthPx else -100f
                    val bX = if (endB != null) (endB.toFloat() / latestDur.toFloat()) * widthPx else -100f

                    val draggingBracket = when {
                        startA != null && endB != null && abs(down.position.x - aX) < 32f -> 1
                        startA != null && endB != null && abs(down.position.x - bX) < 32f -> 2
                        else -> 0
                    }

                    var currentTargetMs = ((down.position.x / widthPx).coerceIn(0f, 1f) * latestDur).roundToLong()
                    var totalHorizontalDragPx = 0f
                    var startedScrubSession = false

                    if (draggingBracket == 0 && tappedBookmark == null) {
                        startedScrubSession = true
                        onScrubStart()
                        localScrubPositionMs = currentTargetMs
                        onScrubMove(currentTargetMs, "1.0x scrub")
                    }

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            val dx = change.positionChange().x
                            totalHorizontalDragPx += abs(dx)
                            val verticalPullUpPx = (down.position.y - change.position.y).coerceAtLeast(0f)

                            val (sensitivity, label) = when {
                                verticalPullUpPx > 140f -> 0.25f to "0.25x fine scrub"
                                verticalPullUpPx > 65f -> 0.50f to "0.50x fine scrub"
                                else -> 1.0f to "1.0x scrub"
                            }

                            if (draggingBracket == 1 && startA != null && endB != null) {
                                val frac = (change.position.x / widthPx).coerceIn(0f, 1f)
                                val newA = (frac * latestDur).roundToLong().coerceAtMost(endB - 250L)
                                onDragLoopBracket(newA, endB)
                            } else if (draggingBracket == 2 && startA != null && endB != null) {
                                val frac = (change.position.x / widthPx).coerceIn(0f, 1f)
                                val newB = (frac * latestDur).roundToLong().coerceAtLeast(startA + 250L)
                                onDragLoopBracket(startA, newB)
                            } else if (tappedBookmark == null || totalHorizontalDragPx > 14f) {
                                if (!startedScrubSession) {
                                    startedScrubSession = true
                                    onScrubStart()
                                }
                                currentTargetMs = if (sensitivity < 0.99f) {
                                    val deltaMs = ((dx / widthPx) * latestDur * sensitivity).roundToLong()
                                    (currentTargetMs + deltaMs).coerceIn(0L, latestDur)
                                } else {
                                    ((change.position.x / widthPx).coerceIn(0f, 1f) * latestDur).roundToLong()
                                }
                                localScrubPositionMs = currentTargetMs
                                onScrubMove(currentTargetMs, label)
                            }
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    if (tappedBookmark != null && totalHorizontalDragPx <= 14f && draggingBracket == 0) {
                        localScrubPositionMs = null
                        onTapBookmarkDiamond(tappedBookmark)
                    } else if (startedScrubSession) {
                        onScrubEnd(currentTargetMs)
                        localScrubPositionMs = null
                    }
                }
            }
    ) {
        val canvasW = size.width
        val centerY = size.height / 2f
        val trackHeight = 6.dp.toPx()

        drawRoundRect(
            color = Color.White.copy(alpha = 0.24f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(canvasW, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f)
        )

        val bufWidth = (bufferedPercentage.coerceIn(0, 100) / 100f) * canvasW
        drawRoundRect(
            color = Color.White.copy(alpha = 0.40f),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(bufWidth, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f)
        )

        val playedFraction = (displayedPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
        val playedWidth = playedFraction * canvasW
        drawRoundRect(
            color = palette.primaryAccent,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(playedWidth, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f)
        )

        if (loopStartMs != null) {
            val aX = ((loopStartMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)) * canvasW
            val bX = if (loopEndMs != null) {
                ((loopEndMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)) * canvasW
            } else aX

            if (loopEndMs != null && bX > aX) {
                drawRect(
                    color = palette.tertiaryLoopAccent.copy(alpha = 0.38f),
                    topLeft = Offset(aX, centerY - 10.dp.toPx()),
                    size = Size(bX - aX, 20.dp.toPx())
                )
            }

            val bracketH = 22.dp.toPx()
            val bracketArm = 6.dp.toPx()
            drawLine(
                color = palette.tertiaryLoopAccent,
                start = Offset(aX, centerY - bracketH / 2f),
                end = Offset(aX, centerY + bracketH / 2f),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = palette.tertiaryLoopAccent,
                start = Offset(aX, centerY - bracketH / 2f),
                end = Offset(aX + bracketArm, centerY - bracketH / 2f),
                strokeWidth = 3.dp.toPx()
            )
            drawLine(
                color = palette.tertiaryLoopAccent,
                start = Offset(aX, centerY + bracketH / 2f),
                end = Offset(aX + bracketArm, centerY + bracketH / 2f),
                strokeWidth = 3.dp.toPx()
            )

            if (loopEndMs != null) {
                drawLine(
                    color = palette.tertiaryLoopAccent,
                    start = Offset(bX, centerY - bracketH / 2f),
                    end = Offset(bX, centerY + bracketH / 2f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = palette.tertiaryLoopAccent,
                    start = Offset(bX - bracketArm, centerY - bracketH / 2f),
                    end = Offset(bX, centerY - bracketH / 2f),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = palette.tertiaryLoopAccent,
                    start = Offset(bX - bracketArm, centerY + bracketH / 2f),
                    end = Offset(bX, centerY + bracketH / 2f),
                    strokeWidth = 3.dp.toPx()
                )
            }
        }

        val diamondRadius = 6.dp.toPx()
        for (bm in bookmarks) {
            val bmX = ((bm.timestampMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)) * canvasW
            val bmY = centerY - 12.dp.toPx()
            val diamondPath = Path().apply {
                moveTo(bmX, bmY - diamondRadius)
                lineTo(bmX + diamondRadius, bmY)
                lineTo(bmX, bmY + diamondRadius)
                lineTo(bmX - diamondRadius, bmY)
                close()
            }
            drawPath(path = diamondPath, color = palette.tertiaryLoopAccent)
        }

        drawCircle(
            color = palette.primaryAccent,
            radius = 8.dp.toPx(),
            center = Offset(playedWidth, centerY)
        )
        drawCircle(
            color = Color.Black,
            radius = 3.dp.toPx(),
            center = Offset(playedWidth, centerY)
        )
    }
}

@Composable
fun ViewportShrinkingSideSheet(
    activeTab: SideSheetTab,
    onSelectTab: (SideSheetTab) -> Unit,
    onClose: () -> Unit,
    palette: VideoDynamicPalette,
    prefs: AppPreferencesState,
    subtitleTracks: List<PlayerTrackOption>,
    subtitlesEnabled: Boolean,
    externalSubtitleLabel: String?,
    subtitleDelayMs: Long,
    subtitleSpeedFactor: Float,
    subtitleAnchorTimeMs: Long,
    audioTracks: List<PlayerTrackOption>,
    isAudioDisabled: Boolean,
    audioDelayMs: Long,
    volumeFraction: Float,
    bookmarks: List<BookmarkEntity>,
    savedLoops: List<SavedLoopEntity>,
    loopStartMs: Long?,
    loopEndMs: Long?,
    playbackSpeed: Float,
    onSelectSubtitleTrack: (PlayerTrackOption?) -> Unit,
    onEnableExternalSubtitle: () -> Unit,
    onPickExternalSubtitle: () -> Unit,
    onAdjustSubtitleDelay: (Long) -> Unit,
    onSetSubtitleDelayDirect: (Long) -> Unit,
    onResetSubtitleDelay: () -> Unit,
    onSetSubtitleSpeed: (Float) -> Unit,
    onAnchorSubtitleSpeedHere: () -> Unit,
    onResetSubtitleAnchor: () -> Unit,
    onUpdateSubtitleStyle: (SubtitleFontChoice?, Float?, Int?, Float?, Float?, Float?, Boolean?, Float?) -> Unit,
    onSelectAudioTrack: (PlayerTrackOption?) -> Unit,
    onPickExternalAudio: () -> Unit,
    onAdjustAudioDelay: (Long) -> Unit,
    onResetAudioDelay: () -> Unit,
    onSetVolumeBoost: (Float) -> Unit,
    onSeekToExact: (Long) -> Unit,
    onAddBookmarkNow: (String) -> Unit,
    onUpdateBookmarkLabel: (BookmarkEntity, String) -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
    onActivateSavedLoop: (SavedLoopEntity) -> Unit,
    onClearActiveLoop: () -> Unit,
    onDeleteSavedLoop: (SavedLoopEntity) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onToggleTopLeftSlot: (TopLeftQuickSlot) -> Unit,
    onToggleTopRightSlot: (TopRightQuickSlot) -> Unit
) {
    // Exact v4.0 side-sheet geometry (340dp, 14dp padding)
    Surface(
        color = Color(0xFF101216).copy(alpha = 0.96f),
        border = BorderStroke(1.dp, palette.specularBorder),
        modifier = Modifier
            .fillMaxHeight()
            .width(340.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = activeTab.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SideSheetTab.entries.forEach { tab ->
                    val selected = tab == activeTab
                    val icon = when (tab) {
                        SideSheetTab.SUBTITLES -> Icons.Outlined.Subtitles
                        SideSheetTab.AUDIO -> Icons.Outlined.Audiotrack
                        SideSheetTab.BOOKMARKS_LOOPS -> Icons.Outlined.Bookmarks
                        SideSheetTab.SPEED -> Icons.Default.Speed
                        SideSheetTab.CUSTOMIZE_CONTROLS -> Icons.Outlined.Tune
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) palette.primaryAccent else Color.White.copy(alpha = 0.08f),
                        onClick = { onSelectTab(tab) },
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                icon,
                                contentDescription = tab.title,
                                tint = if (selected) palette.onPrimaryAccent else Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = palette.specularBorder
            )

            Box(modifier = Modifier.weight(1f)) {
                when (activeTab) {
                    SideSheetTab.SUBTITLES -> SubtitleSideSheetContent(
                        palette = palette,
                        prefs = prefs,
                        subtitleTracks = subtitleTracks,
                        subtitlesEnabled = subtitlesEnabled,
                        externalSubtitleLabel = externalSubtitleLabel,
                        subtitleDelayMs = subtitleDelayMs,
                        subtitleSpeedFactor = subtitleSpeedFactor,
                        subtitleAnchorTimeMs = subtitleAnchorTimeMs,
                        onSelectSubtitleTrack = onSelectSubtitleTrack,
                        onEnableExternalSubtitle = onEnableExternalSubtitle,
                        onPickExternalSubtitle = onPickExternalSubtitle,
                        onAdjustSubtitleDelay = onAdjustSubtitleDelay,
                        onSetSubtitleDelayDirect = onSetSubtitleDelayDirect,
                        onResetSubtitleDelay = onResetSubtitleDelay,
                        onSetSubtitleSpeed = onSetSubtitleSpeed,
                        onAnchorSubtitleSpeedHere = onAnchorSubtitleSpeedHere,
                        onResetSubtitleAnchor = onResetSubtitleAnchor,
                        onUpdateSubtitleStyle = onUpdateSubtitleStyle
                    )
                    SideSheetTab.AUDIO -> AudioSideSheetContent(
                        palette = palette,
                        audioTracks = audioTracks,
                        isAudioDisabled = isAudioDisabled,
                        audioDelayMs = audioDelayMs,
                        volumeFraction = volumeFraction,
                        onSelectAudioTrack = onSelectAudioTrack,
                        onPickExternalAudio = onPickExternalAudio,
                        onAdjustAudioDelay = onAdjustAudioDelay,
                        onResetAudioDelay = onResetAudioDelay,
                        onSetVolumeBoost = onSetVolumeBoost
                    )
                    SideSheetTab.BOOKMARKS_LOOPS -> BookmarksAndLoopsSideSheetContent(
                        palette = palette,
                        bookmarks = bookmarks,
                        savedLoops = savedLoops,
                        loopStartMs = loopStartMs,
                        loopEndMs = loopEndMs,
                        onSeekToExact = onSeekToExact,
                        onAddBookmarkNow = onAddBookmarkNow,
                        onUpdateBookmarkLabel = onUpdateBookmarkLabel,
                        onDeleteBookmark = onDeleteBookmark,
                        onActivateSavedLoop = onActivateSavedLoop,
                        onClearActiveLoop = onClearActiveLoop,
                        onDeleteSavedLoop = onDeleteSavedLoop
                    )
                    SideSheetTab.SPEED -> SpeedSideSheetContent(
                        palette = palette,
                        playbackSpeed = playbackSpeed,
                        onSetPlaybackSpeed = onSetPlaybackSpeed
                    )
                    SideSheetTab.CUSTOMIZE_CONTROLS -> CustomizeHudControlsContent(
                        palette = palette,
                        enabledTopLeftSlots = prefs.enabledTopLeftSlots,
                        enabledTopRightSlots = prefs.enabledTopRightSlots,
                        onToggleTopLeftSlot = onToggleTopLeftSlot,
                        onToggleTopRightSlot = onToggleTopRightSlot
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleSideSheetContent(
    palette: VideoDynamicPalette,
    prefs: AppPreferencesState,
    subtitleTracks: List<PlayerTrackOption>,
    subtitlesEnabled: Boolean,
    externalSubtitleLabel: String?,
    subtitleDelayMs: Long,
    subtitleSpeedFactor: Float,
    subtitleAnchorTimeMs: Long,
    onSelectSubtitleTrack: (PlayerTrackOption?) -> Unit,
    onEnableExternalSubtitle: () -> Unit,
    onPickExternalSubtitle: () -> Unit,
    onAdjustSubtitleDelay: (Long) -> Unit,
    onSetSubtitleDelayDirect: (Long) -> Unit,
    onResetSubtitleDelay: () -> Unit,
    onSetSubtitleSpeed: (Float) -> Unit,
    onAnchorSubtitleSpeedHere: () -> Unit,
    onResetSubtitleAnchor: () -> Unit,
    onUpdateSubtitleStyle: (SubtitleFontChoice?, Float?, Int?, Float?, Float?, Float?, Boolean?, Float?) -> Unit
) {
    var subSection by remember { mutableIntStateOf(0) }
    var showManualDelayDialog by remember { mutableStateOf(false) }
    var showManualSpeedDialog by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Tracks", "Sync & FPS", "Fonts").forEachIndexed { idx, label ->
                FilterChip(
                    selected = subSection == idx,
                    onClick = { subSection = idx },
                    label = {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (subSection) {
                0 -> {
                    OutlinedButton(
                        onClick = onPickExternalSubtitle,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Load subtitle file")
                    }

                    if (externalSubtitleLabel != null) {
                        TrackRowItem(
                            title = "External: $externalSubtitleLabel",
                            badge = if (subtitlesEnabled) "Active" else "Off",
                            selected = subtitlesEnabled,
                            palette = palette,
                            onClick = onEnableExternalSubtitle
                        )
                    }

                    TrackRowItem(
                        title = "Disable subtitles",
                        badge = "Off",
                        selected = !subtitlesEnabled,
                        palette = palette,
                        onClick = { onSelectSubtitleTrack(null) }
                    )

                    subtitleTracks.forEach { track ->
                        TrackRowItem(
                            title = "${track.label} (${track.language})",
                            badge = track.codecBadge,
                            selected = subtitlesEnabled && externalSubtitleLabel == null && track.isSelected,
                            palette = palette,
                            onClick = { onSelectSubtitleTrack(track) }
                        )
                    }
                }

                1 -> {
                    // CARD 1: DELAY OFFSET (Tappable readout for manual input + 4 hold-to-repeat step buttons)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.05f),
                        border = BorderStroke(1.dp, palette.specularBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = palette.primaryAccent.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, palette.primaryAccent.copy(alpha = 0.45f)),
                                    onClick = { showManualDelayDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Delay: ${FormatUtils.formatSignedSeconds(subtitleDelayMs)}",
                                            style = TabularMonoStyle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.primaryAccent
                                        )
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Type delay",
                                            tint = palette.primaryAccent,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }

                                MicroTunerChip(
                                    text = "Reset",
                                    highlightColor = palette.tertiaryLoopAccent,
                                    onClick = onResetSubtitleDelay
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                HoldRepeatStepButton(
                                    text = "-0.5s",
                                    modifier = Modifier.weight(1f),
                                    onStep = { onAdjustSubtitleDelay(-500L) }
                                )
                                HoldRepeatStepButton(
                                    text = "-0.05s",
                                    modifier = Modifier.weight(1f),
                                    onStep = { onAdjustSubtitleDelay(-50L) }
                                )
                                HoldRepeatStepButton(
                                    text = "+0.05s",
                                    modifier = Modifier.weight(1f),
                                    onStep = { onAdjustSubtitleDelay(50L) }
                                )
                                HoldRepeatStepButton(
                                    text = "+0.5s",
                                    modifier = Modifier.weight(1f),
                                    onStep = { onAdjustSubtitleDelay(500L) }
                                )
                            }
                        }
                    }

                    // CARD 2: SUBTITLE SPEED & ISOLATED ANCHOR ROW WITH RESET
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.05f),
                        border = BorderStroke(1.dp, palette.specularBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = palette.primaryAccent.copy(alpha = 0.14f),
                                    border = BorderStroke(1.dp, palette.primaryAccent.copy(alpha = 0.45f)),
                                    onClick = { showManualSpeedDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = String.format(Locale.US, "Speed: %.4fx", subtitleSpeedFactor),
                                            style = TabularMonoStyle,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.primaryAccent
                                        )
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Type speed",
                                            tint = palette.primaryAccent,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }

                                MicroTunerChip(
                                    text = "Reset",
                                    highlightColor = palette.tertiaryLoopAccent,
                                    onClick = { onSetSubtitleSpeed(1.0000f) }
                                )
                            }

                            // Granular (-0.1% / +0.1%) AND Big Step (-1.0% / +1.0%) with Hold-to-Repeat
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                HoldRepeatStepButton(
                                    text = "-1.0%",
                                    modifier = Modifier.weight(1f),
                                    onStep = { onSetSubtitleSpeed(subtitleSpeedFactor - 0.010f) }
                                )
                                HoldRepeatStepButton(
                                    text = "-0.1%",
                                    modifier = Modifier.weight(1f),
                                    onStep = { onSetSubtitleSpeed(subtitleSpeedFactor - 0.001f) }
                                )
                                HoldRepeatStepButton(
                                    text = "+0.1%",
                                    modifier = Modifier.weight(1f),
                                    onStep = { onSetSubtitleSpeed(subtitleSpeedFactor + 0.001f) }
                                )
                                HoldRepeatStepButton(
                                    text = "+1.0%",
                                    modifier = Modifier.weight(1f),
                                    onStep = { onSetSubtitleSpeed(subtitleSpeedFactor + 0.010f) }
                                )
                            }

                            HorizontalDivider(color = palette.specularBorder.copy(alpha = 0.5f))

                            // Isolated Anchor Row — separated from speed buttons with its own Reset button!
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Speed anchor",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = FormatUtils.formatDurationMs(subtitleAnchorTimeMs),
                                        style = TabularMonoStyle,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.tertiaryLoopAccent
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MicroTunerChip(
                                        text = "Anchor now",
                                        highlightColor = palette.tertiaryLoopAccent,
                                        onClick = onAnchorSubtitleSpeedHere
                                    )
                                    if (subtitleAnchorTimeMs > 0L) {
                                        MicroTunerChip(
                                            text = "Reset",
                                            highlightColor = Color(0xFFFF6B6B),
                                            onClick = onResetSubtitleAnchor
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        "Frame rate drift presets",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    val fpsPresets = listOf(
                        "23.976 \u2192 25 fps" to 0.9590f,
                        "25 \u2192 23.976 fps" to 1.0427f,
                        "24 \u2192 23.976 fps" to 1.0010f,
                        "Normal (1.0000x)" to 1.0000f
                    )
                    fpsPresets.forEach { (label, factor) ->
                        TrackRowItem(
                            title = label,
                            badge = String.format(Locale.US, "%.4fx", factor),
                            selected = abs(subtitleSpeedFactor - factor) < 0.0004f,
                            palette = palette,
                            onClick = { onSetSubtitleSpeed(factor) }
                        )
                    }
                }

                2 -> {
                    // 3 Pure Swatches + 1 Custom Color Dialog Trigger (No text labels that clip!)
                    Text("Caption color", style = MaterialTheme.typography.labelMedium, color = palette.primaryAccent)
                    val presetSwatches = listOf(
                        0xFFFFFFFF.toInt(),
                        0xFFF5E6C8.toInt(),
                        0xFF18181B.toInt()
                    )
                    val isCustomColorSelected = prefs.subtitleTextColorArgb !in presetSwatches

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        presetSwatches.forEach { argb ->
                            val selected = prefs.subtitleTextColorArgb == argb
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .border(
                                        width = if (selected) 2.5.dp else 1.dp,
                                        color = if (selected) palette.primaryAccent else Color.White.copy(alpha = 0.45f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        onUpdateSubtitleStyle(null, null, argb, null, null, null, null, null)
                                    }
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (argb == 0xFF18181B.toInt()) Color.White else Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Custom Color Button (Opens clean spacious modal dialog)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isCustomColorSelected) Color(prefs.subtitleTextColorArgb)
                                    else Color.White.copy(alpha = 0.12f)
                                )
                                .border(
                                    width = if (isCustomColorSelected) 2.5.dp else 1.dp,
                                    color = if (isCustomColorSelected) palette.primaryAccent else Color.White.copy(alpha = 0.45f),
                                    shape = CircleShape
                                )
                                .clickable { showCustomColorDialog = true }
                        ) {
                            Icon(
                                Icons.Outlined.Palette,
                                contentDescription = "Custom color",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Text(
                        "Font size: ${prefs.subtitleFontSizeSp.roundToInt()} sp",
                        style = TabularNumeralStyle,
                        color = Color.White
                    )
                    Slider(
                        value = prefs.subtitleFontSizeSp,
                        onValueChange = { onUpdateSubtitleStyle(null, it, null, null, null, null, null, null) },
                        valueRange = 14f..42f
                    )

                    Text(
                        "Vertical bottom position: ${prefs.subtitleBottomMarginDp.roundToInt()} dp",
                        style = TabularNumeralStyle,
                        color = Color.White
                    )
                    Slider(
                        value = prefs.subtitleBottomMarginDp,
                        onValueChange = { onUpdateSubtitleStyle(null, null, null, null, null, null, null, it) },
                        valueRange = 8f..120f
                    )

                    HorizontalDivider(color = palette.specularBorder)

                    // Single Drop Shadow Slider (0% Off to 100%)
                    Text(
                        "Drop shadow: ${(prefs.subtitleDropShadowStrength * 100).roundToInt()}%",
                        style = TabularNumeralStyle,
                        color = Color.White
                    )
                    Slider(
                        value = prefs.subtitleDropShadowStrength,
                        onValueChange = { onUpdateSubtitleStyle(null, null, null, null, it, null, null, null) },
                        valueRange = 0f..1f
                    )

                    // Single Ultra-Thin Hairline Border Slider (0.0dp Off to 1.5dp Max)
                    val borderLabel = if (prefs.subtitleOutlineWidth < 0.05f) {
                        "Off"
                    } else {
                        String.format(Locale.US, "%.2f dp", prefs.subtitleOutlineWidth)
                    }
                    Text(
                        "Thin border: $borderLabel",
                        style = TabularNumeralStyle,
                        color = Color.White
                    )
                    Slider(
                        value = prefs.subtitleOutlineWidth,
                        onValueChange = { onUpdateSubtitleStyle(null, null, null, null, null, it, null, null) },
                        valueRange = 0f..1.5f
                    )

                    Text(
                        "Background box opacity: ${(prefs.subtitleBgOpacity * 100).roundToInt()}%",
                        style = TabularNumeralStyle,
                        color = Color.White
                    )
                    Slider(
                        value = prefs.subtitleBgOpacity,
                        onValueChange = { onUpdateSubtitleStyle(null, null, null, it, null, null, null, null) },
                        valueRange = 0f..1f
                    )

                    HorizontalDivider(color = palette.specularBorder)

                    Text("Subtitle font family", style = MaterialTheme.typography.labelMedium, color = palette.primaryAccent)
                    SubtitleFontChoice.entries.forEach { fontChoice ->
                        TrackRowItem(
                            title = fontChoice.label,
                            badge = "Font",
                            selected = prefs.subtitleFont == fontChoice,
                            palette = palette,
                            onClick = { onUpdateSubtitleStyle(fontChoice, null, null, null, null, null, null, null) }
                        )
                    }
                }
            }
        }
    }

    // Manual Numeric Input Dialog for Subtitle Delay
    if (showManualDelayDialog) {
        var rawDelaySec by remember {
            mutableStateOf(String.format(Locale.US, "%.2f", subtitleDelayMs / 1000.0))
        }
        AlertDialog(
            onDismissRequest = { showManualDelayDialog = false },
            title = { Text("Set subtitle delay (seconds)") },
            text = {
                OutlinedTextField(
                    value = rawDelaySec,
                    onValueChange = { rawDelaySec = it },
                    label = { Text("Seconds (e.g. -1.25 or 0.50)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    rawDelaySec.trim().toDoubleOrNull()?.let { sec ->
                        onSetSubtitleDelayDirect((sec * 1000.0).roundToLong())
                    }
                    showManualDelayDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDelayDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Manual Numeric Input Dialog for Subtitle Speed
    if (showManualSpeedDialog) {
        var rawSpeedFactor by remember {
            mutableStateOf(String.format(Locale.US, "%.4f", subtitleSpeedFactor))
        }
        AlertDialog(
            onDismissRequest = { showManualSpeedDialog = false },
            title = { Text("Set subtitle speed factor") },
            text = {
                OutlinedTextField(
                    value = rawSpeedFactor,
                    onValueChange = { rawSpeedFactor = it },
                    label = { Text("Multiplier (0.8500 to 1.1500)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    rawSpeedFactor.trim().toFloatOrNull()?.let { factor ->
                        onSetSubtitleSpeed(factor)
                    }
                    showManualSpeedDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualSpeedDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clean Popup Dialog for Custom Subtitle Color
    if (showCustomColorDialog) {
        val initialHsv = remember(prefs.subtitleTextColorArgb) {
            FloatArray(3).also { android.graphics.Color.colorToHSV(prefs.subtitleTextColorArgb, it) }
        }
        var hue by remember { mutableFloatStateOf(initialHsv[0]) }
        var saturation by remember { mutableFloatStateOf(initialHsv[1].coerceAtLeast(0.15f)) }
        var valueBri by remember { mutableFloatStateOf(initialHsv[2].coerceAtLeast(0.25f)) }
        val previewColor = remember(hue, saturation, valueBri) {
            Color.hsv(hue, saturation, valueBri)
        }

        AlertDialog(
            onDismissRequest = { showCustomColorDialog = false },
            title = { Text("Custom subtitle color") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    ) {
                        Text(
                            text = "Subtitle Preview",
                            color = previewColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Text("Hue", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = hue,
                        onValueChange = { hue = it },
                        valueRange = 0f..360f
                    )

                    Text("Saturation", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = saturation,
                        onValueChange = { saturation = it },
                        valueRange = 0f..1f
                    )

                    Text("Brightness", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = valueBri,
                        onValueChange = { valueBri = it },
                        valueRange = 0.1f..1f
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateSubtitleStyle(null, null, previewColor.toArgb(), null, null, null, null, null)
                    showCustomColorDialog = false
                }) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomColorDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AudioSideSheetContent(
    palette: VideoDynamicPalette,
    audioTracks: List<PlayerTrackOption>,
    isAudioDisabled: Boolean,
    audioDelayMs: Long,
    volumeFraction: Float,
    onSelectAudioTrack: (PlayerTrackOption?) -> Unit,
    onPickExternalAudio: () -> Unit,
    onAdjustAudioDelay: (Long) -> Unit,
    onResetAudioDelay: () -> Unit,
    onSetVolumeBoost: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TrackRowItem(
            title = "Disable audio (Mute video track)",
            badge = "Mute",
            selected = isAudioDisabled,
            palette = palette,
            onClick = { onSelectAudioTrack(null) }
        )

        Text("Audio tracks", style = MaterialTheme.typography.labelMedium, color = palette.primaryAccent)
        audioTracks.forEach { track ->
            TrackRowItem(
                title = "${track.label} (${track.language})",
                badge = "${track.codecBadge} • ${track.detailsBadge}",
                selected = !isAudioDisabled && track.isSelected,
                palette = palette,
                onClick = { onSelectAudioTrack(track) }
            )
        }

        OutlinedButton(
            onClick = onPickExternalAudio,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Load external audio file")
        }

        HorizontalDivider(color = palette.specularBorder)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Audio sync: ${FormatUtils.formatSignedSeconds(audioDelayMs)}",
                style = TabularMonoStyle,
                fontWeight = FontWeight.Bold,
                color = palette.primaryAccent
            )
            MicroTunerChip(
                text = "Reset",
                highlightColor = palette.tertiaryLoopAccent,
                onClick = onResetAudioDelay
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HoldRepeatStepButton(
                text = "-0.25s",
                modifier = Modifier.weight(1f),
                onStep = { onAdjustAudioDelay(-250L) }
            )
            HoldRepeatStepButton(
                text = "-0.05s",
                modifier = Modifier.weight(1f),
                onStep = { onAdjustAudioDelay(-50L) }
            )
            HoldRepeatStepButton(
                text = "+0.05s",
                modifier = Modifier.weight(1f),
                onStep = { onAdjustAudioDelay(50L) }
            )
            HoldRepeatStepButton(
                text = "+0.25s",
                modifier = Modifier.weight(1f),
                onStep = { onAdjustAudioDelay(250L) }
            )
        }

        HorizontalDivider(color = palette.specularBorder)

        val pct = (volumeFraction * 100f).roundToInt()
        Text(
            text = "Volume boost: $pct%",
            style = TabularNumeralStyle,
            fontWeight = FontWeight.Bold,
            color = if (pct > 100) palette.tertiaryLoopAccent else Color.White
        )
        Slider(
            value = volumeFraction,
            onValueChange = onSetVolumeBoost,
            valueRange = 0f..2.0f
        )
    }
}

@Composable
private fun BookmarksAndLoopsSideSheetContent(
    palette: VideoDynamicPalette,
    bookmarks: List<BookmarkEntity>,
    savedLoops: List<SavedLoopEntity>,
    loopStartMs: Long?,
    loopEndMs: Long?,
    onSeekToExact: (Long) -> Unit,
    onAddBookmarkNow: (String) -> Unit,
    onUpdateBookmarkLabel: (BookmarkEntity, String) -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
    onActivateSavedLoop: (SavedLoopEntity) -> Unit,
    onClearActiveLoop: () -> Unit,
    onDeleteSavedLoop: (SavedLoopEntity) -> Unit
) {
    var editingBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (loopStartMs != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.tertiaryLoopAccent.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, palette.tertiaryLoopAccent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Active loop running", style = MaterialTheme.typography.labelSmall, color = palette.tertiaryLoopAccent)
                        val endStr = if (loopEndMs != null) FormatUtils.formatDurationMs(loopEndMs) else "..."
                        Text("${FormatUtils.formatDurationMs(loopStartMs)} - $endStr", style = TabularMonoStyle, fontSize = 12.sp, color = Color.White)
                    }
                    TextButton(onClick = onClearActiveLoop) {
                        Text("Exit loop", color = Color(0xFFFF6B6B))
                    }
                }
            }
        }

        Text(
            "Saved A-B loops (${savedLoops.size})",
            style = MaterialTheme.typography.labelMedium,
            color = palette.tertiaryLoopAccent
        )
        if (savedLoops.isEmpty()) {
            Text(
                "No saved loops yet. Set a loop and tap Save to switch between multiple loops anytime.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.65f)
            )
        } else {
            savedLoops.forEach { loop ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = palette.tertiaryLoopAccent.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, palette.tertiaryLoopAccent.copy(alpha = 0.5f)),
                    onClick = { onActivateSavedLoop(loop) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(loop.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "${FormatUtils.formatPreciseCentiseconds(loop.startMs)} - ${FormatUtils.formatPreciseCentiseconds(loop.endMs)}",
                                style = TabularMonoStyle,
                                fontSize = 11.sp,
                                color = palette.tertiaryLoopAccent
                            )
                        }
                        IconButton(onClick = { onDeleteSavedLoop(loop) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFFF5C5C), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = palette.specularBorder)

        Button(
            onClick = { onAddBookmarkNow("") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.BookmarkAdd, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Pin bookmark at current frame", color = palette.onPrimaryAccent)
        }

        Text(
            "Bookmarks (${bookmarks.size})",
            style = MaterialTheme.typography.labelMedium,
            color = palette.primaryAccent
        )
        bookmarks.forEach { bm ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.07f),
                border = BorderStroke(1.dp, palette.specularBorder),
                onClick = { onSeekToExact(bm.timestampMs) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(bm.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            FormatUtils.formatPreciseCentiseconds(bm.timestampMs),
                            style = TabularMonoStyle,
                            fontSize = 11.sp,
                            color = palette.tertiaryLoopAccent
                        )
                    }
                    IconButton(onClick = { editingBookmark = bm }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { onDeleteBookmark(bm) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFFF5C5C), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    editingBookmark?.let { target ->
        var labelText by remember(target) { mutableStateOf(target.label) }
        AlertDialog(
            onDismissRequest = { editingBookmark = null },
            title = { Text("Edit bookmark") },
            text = {
                OutlinedTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onUpdateBookmarkLabel(target, labelText)
                    editingBookmark = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingBookmark = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Clean, Minimalist Playback Speed Panel:
 * Prominent numeric readout + hold-to-repeat +/- buttons + smooth slider + 5 essential pills.
 */
@Composable
private fun SpeedSideSheetContent(
    palette: VideoDynamicPalette,
    playbackSpeed: Float,
    onSetPlaybackSpeed: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.05f),
            border = BorderStroke(1.dp, palette.specularBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HoldRepeatStepButton(
                        text = "-0.05x",
                        modifier = Modifier.width(68.dp),
                        onStep = { onSetPlaybackSpeed((playbackSpeed - 0.05f).coerceAtLeast(0.25f)) }
                    )

                    Text(
                        text = String.format(Locale.US, "%.2fx", playbackSpeed),
                        style = TabularMonoStyle,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primaryAccent
                    )

                    HoldRepeatStepButton(
                        text = "+0.05x",
                        modifier = Modifier.width(68.dp),
                        onStep = { onSetPlaybackSpeed((playbackSpeed + 0.05f).coerceAtMost(3.5f)) }
                    )
                }

                Slider(
                    value = playbackSpeed,
                    onValueChange = { onSetPlaybackSpeed((it * 20f).roundToInt() / 20f) },
                    valueRange = 0.25f..3.0f
                )
            }
        }

        val essentialPresets = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            essentialPresets.forEach { spd ->
                val selected = abs(playbackSpeed - spd) < 0.02f
                val label = when (spd) {
                    1.0f -> "1x"
                    1.5f -> "1.5x"
                    2.0f -> "2x"
                    else -> String.format(Locale.US, "%.2fx", spd)
                }
                Surface(
                    shape = CircleShape,
                    color = if (selected) palette.primaryAccent else Color.White.copy(alpha = 0.07f),
                    border = BorderStroke(
                        1.dp,
                        if (selected) palette.primaryAccent else palette.specularBorder
                    ),
                    onClick = { onSetPlaybackSpeed(spd) },
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = TabularMonoStyle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) palette.onPrimaryAccent else Color.White,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomizeHudControlsContent(
    palette: VideoDynamicPalette,
    enabledTopLeftSlots: List<TopLeftQuickSlot>,
    enabledTopRightSlots: List<TopRightQuickSlot>,
    onToggleTopLeftSlot: (TopLeftQuickSlot) -> Unit,
    onToggleTopRightSlot: (TopRightQuickSlot) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Any button hidden from the top bar automatically moves into the top-right Menu.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.75f)
        )

        Text(
            text = "Top-left buttons (Under video title)",
            style = MaterialTheme.typography.labelMedium,
            color = palette.primaryAccent
        )
        TopLeftQuickSlot.entries.forEach { slot ->
            val isSelected = slot in enabledTopLeftSlots
            TrackRowItem(
                title = slot.label,
                badge = if (isSelected) "Top-left" else "In menu",
                selected = isSelected,
                palette = palette,
                onClick = { onToggleTopLeftSlot(slot) }
            )
        }

        HorizontalDivider(color = palette.specularBorder)

        Text(
            text = "Top-right buttons (Beside menu)",
            style = MaterialTheme.typography.labelMedium,
            color = palette.primaryAccent
        )
        TopRightQuickSlot.entries.forEach { slot ->
            val isSelected = slot in enabledTopRightSlots
            TrackRowItem(
                title = slot.label,
                badge = if (isSelected) "Top-right" else "In menu",
                selected = isSelected,
                palette = palette,
                onClick = { onToggleTopRightSlot(slot) }
            )
        }
    }
}

@Composable
private fun TrackRowItem(
    title: String,
    badge: String,
    selected: Boolean,
    palette: VideoDynamicPalette,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) palette.primaryAccent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f),
        border = BorderStroke(
            1.dp,
            if (selected) palette.primaryAccent else palette.specularBorder
        ),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = badge,
                style = TabularMonoStyle,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) palette.primaryAccent else Color.White.copy(alpha = 0.7f)
            )
        }
    }
}
