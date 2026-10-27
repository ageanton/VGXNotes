package com.flagship.videoplayer.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Display
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import com.flagship.videoplayer.data.*
import com.flagship.videoplayer.ui.theme.FormatUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class DelayingAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var targetDelayMs: Long = 0L

    private var ringBuffer: ByteArray = ByteArray(0)
    private var writePos: Int = 0
    private var readPos: Int = 0
    private var bufferedBytes: Int = 0
    private var targetDelayBytes: Int = 0
    private var pendingNegativeSkipBytes: Int = 0

    fun setDelayMs(delayMs: Long) {
        val clamped = delayMs.coerceIn(-5000L, 5000L)
        if (targetDelayMs != clamped) {
            targetDelayMs = clamped
            recalculateDelayBuffer()
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    private fun recalculateDelayBuffer() {
        val format = inputAudioFormat
        if (format == AudioProcessor.AudioFormat.NOT_SET || format.bytesPerFrame <= 0) return

        val delay = targetDelayMs
        val frameSize = format.bytesPerFrame
        if (delay > 0L) {
            val rawBytes = ((delay * format.sampleRate.toLong() * frameSize.toLong()) / 1000L).toInt()
            val alignedBytes = (rawBytes / frameSize) * frameSize
            val maxCapacity = ((5000L * format.sampleRate.toLong() * frameSize.toLong()) / 1000L).toInt() + frameSize * 64
            synchronized(this) {
                if (ringBuffer.size < maxCapacity) {
                    ringBuffer = ByteArray(maxCapacity)
                }
                targetDelayBytes = alignedBytes.coerceAtMost(ringBuffer.size)
                writePos = 0
                readPos = 0
                bufferedBytes = 0
                pendingNegativeSkipBytes = 0
            }
        } else if (delay < 0L) {
            val rawSkip = ((abs(delay) * format.sampleRate.toLong() * frameSize.toLong()) / 1000L).toInt()
            val alignedSkip = (rawSkip / frameSize) * frameSize
            synchronized(this) {
                targetDelayBytes = 0
                bufferedBytes = 0
                writePos = 0
                readPos = 0
                pendingNegativeSkipBytes = alignedSkip
            }
        } else {
            synchronized(this) {
                targetDelayBytes = 0
                bufferedBytes = 0
                writePos = 0
                readPos = 0
                pendingNegativeSkipBytes = 0
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val delay = targetDelayMs
        if (delay == 0L) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        synchronized(this) {
            if (delay < 0L) {
                if (pendingNegativeSkipBytes > 0) {
                    val toSkip = minOf(remaining, pendingNegativeSkipBytes)
                    inputBuffer.position(inputBuffer.position() + toSkip)
                    pendingNegativeSkipBytes -= toSkip
                }
                val left = inputBuffer.remaining()
                if (left > 0) {
                    val out = replaceOutputBuffer(left)
                    out.put(inputBuffer)
                    out.flip()
                }
                return
            }

            if (ringBuffer.isEmpty() || targetDelayBytes == 0) {
                recalculateDelayBuffer()
            }
            if (ringBuffer.isEmpty() || targetDelayBytes <= 0) {
                val out = replaceOutputBuffer(remaining)
                out.put(inputBuffer)
                out.flip()
                return
            }

            val out = replaceOutputBuffer(remaining)
            val tempIn = ByteArray(remaining)
            inputBuffer.get(tempIn)

            for (i in 0 until remaining) {
                if (bufferedBytes < targetDelayBytes) {
                    ringBuffer[writePos] = tempIn[i]
                    writePos = (writePos + 1) % ringBuffer.size
                    bufferedBytes++
                    out.put(0.toByte())
                } else {
                    val delayedByte = ringBuffer[readPos]
                    readPos = (readPos + 1) % ringBuffer.size
                    ringBuffer[writePos] = tempIn[i]
                    writePos = (writePos + 1) % ringBuffer.size
                    out.put(delayedByte)
                }
            }
            out.flip()
        }
    }

    override fun onFlush() {
        synchronized(this) { recalculateDelayBuffer() }
    }

    override fun onReset() {
        synchronized(this) {
            ringBuffer = ByteArray(0)
            writePos = 0
            readPos = 0
            bufferedBytes = 0
            targetDelayBytes = 0
            pendingNegativeSkipBytes = 0
        }
    }
}

class ConfigurableRenderersFactory(
    context: Context,
    private val decoderMode: DecoderMode,
    private val delayingAudioProcessor: DelayingAudioProcessor
) : DefaultRenderersFactory(context) {

    init {
        val extMode = when (decoderMode) {
            DecoderMode.HW -> EXTENSION_RENDERER_MODE_OFF
            DecoderMode.HW_PLUS -> EXTENSION_RENDERER_MODE_ON
            DecoderMode.SW -> EXTENSION_RENDERER_MODE_PREFER
        }
        setExtensionRendererMode(extMode)
        setEnableDecoderFallback(true)
        if (decoderMode == DecoderMode.HW_PLUS || decoderMode == DecoderMode.SW) {
            forceEnableMediaCodecAsynchronousQueueing()
        }
        setMediaCodecSelector(buildModeSpecificSelector(decoderMode))
    }

    private fun buildModeSpecificSelector(mode: DecoderMode): MediaCodecSelector {
        return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaultList = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (defaultList.isEmpty()) return@MediaCodecSelector defaultList

            when (mode) {
                DecoderMode.HW -> {
                    val hwOnly = defaultList.filter { isHardwareCodec(it) }
                    hwOnly.ifEmpty { defaultList }
                }
                DecoderMode.HW_PLUS -> {
                    defaultList.sortedWith(
                        compareByDescending<MediaCodecInfo> { isHardwareCodec(it) }
                            .thenByDescending { isModernC2SoftwareCodec(it) }
                    )
                }
                DecoderMode.SW -> {
                    val swCodecs = defaultList.filter { !isHardwareCodec(it) }
                        .sortedByDescending { isModernC2SoftwareCodec(it) }
                    val hwCodecs = defaultList.filter { isHardwareCodec(it) }
                    if (swCodecs.isNotEmpty()) swCodecs + hwCodecs else defaultList
                }
            }
        }
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.hardwareAccelerated && !info.softwareOnly
        }
        val lower = info.name.lowercase(Locale.US)
        val isSw = lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.contains(".sw.") ||
            lower.contains("ffmpeg")
        return !isSw
    }

    private fun isModernC2SoftwareCodec(info: MediaCodecInfo): Boolean {
        val lower = info.name.lowercase(Locale.US)
        return lower.startsWith("c2.android.") || lower.contains("c2.")
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(delayingAudioProcessor))
            .build()
    }
}

object HdrDisplayHelper {
    fun doesDeviceDisplaySupportHdr(context: Context): Boolean {
        return runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching false
            @Suppress("DEPRECATION")
            val caps = display.hdrCapabilities ?: return@runCatching false
            @Suppress("DEPRECATION")
            caps.supportedHdrTypes.isNotEmpty()
        }.getOrDefault(false)
    }

    fun detectHdrBadgeFromColorInfo(colorInfo: ColorInfo?, sampleMime: String?): Pair<Boolean, String> {
        val mimeLower = sampleMime?.lowercase(Locale.US) ?: ""
        if (mimeLower.contains("dolby-vision") || mimeLower.contains("dvhe")) {
            return true to "Dolby Vision"
        }
        if (colorInfo == null) return false to "SDR"
        return when (colorInfo.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> true to "HDR10 PQ"
            C.COLOR_TRANSFER_HLG -> true to "HLG HDR"
            else -> {
                if (colorInfo.colorSpace == C.COLOR_SPACE_BT2020) {
                    true to "BT.2020 HDR"
                } else {
                    false to "SDR"
                }
            }
        }
    }
}

data class TimedSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object MultiFormatSubtitleEngine {

    fun computeEffectiveTimestamp(
        originalTimeMs: Long,
        anchorTimeMs: Long,
        speedFactor: Float,
        delayMs: Long
    ): Long {
        return ((originalTimeMs - anchorTimeMs) * speedFactor.toDouble()).roundToLong() + anchorTimeMs + delayMs
    }

    fun findActiveCuesAtPosition(
        cues: List<TimedSubtitleCue>,
        currentPositionMs: Long,
        anchorTimeMs: Long,
        speedFactor: Float,
        delayMs: Long
    ): List<String> {
        if (cues.isEmpty()) return emptyList()
        val result = ArrayList<String>(2)
        for (cue in cues) {
            val effStart = computeEffectiveTimestamp(cue.startMs, anchorTimeMs, speedFactor, delayMs)
            val effEnd = computeEffectiveTimestamp(cue.endMs, anchorTimeMs, speedFactor, delayMs)
            if (currentPositionMs in effStart..effEnd) {
                result.add(cue.text)
            }
        }
        return result
    }

    suspend fun parseSubtitleUri(
        context: Context,
        uri: Uri,
        displayNameHint: String = "",
        videoFps: Float = 23.976f
    ): List<TimedSubtitleCue> = withContext(Dispatchers.IO) {
        val rawLines = mutableListOf<String>()
        runCatching {
            if (uri.scheme == "file") {
                File(uri.path ?: "").bufferedReader(Charsets.UTF_8).useLines { lines ->
                    rawLines.addAll(lines)
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                        rawLines.addAll(lines)
                    }
                }
            }
        }
        if (rawLines.isEmpty()) return@withContext emptyList()
        parseLinesAutoDetect(rawLines, displayNameHint, videoFps)
    }

    private fun parseLinesAutoDetect(
        lines: List<String>,
        nameHint: String,
        videoFps: Float
    ): List<TimedSubtitleCue> {
        val lowerName = nameHint.lowercase()
        val joinedSample = lines.take(25).joinToString("\n")
        return when {
            lowerName.endsWith(".ass") || lowerName.endsWith(".ssa") ||
                joinedSample.contains("[Events]", ignoreCase = true) ||
                joinedSample.contains("Dialogue:", ignoreCase = true) -> parseAssSsa(lines)
            lowerName.endsWith(".sub") || joinedSample.contains(Regex("^\\{\\d+\\}\\{\\d+\\}")) ->
                parseMicroDvdSub(lines, videoFps)
            lowerName.endsWith(".ttml") || lowerName.endsWith(".xml") ||
                joinedSample.contains("<tt", ignoreCase = true) -> parseTtml(lines)
            else -> parseSrtOrWebVtt(lines)
        }
    }

    private fun parseSrtOrWebVtt(lines: List<String>): List<TimedSubtitleCue> {
        val cues = mutableListOf<TimedSubtitleCue>()
        var i = 0
        val arrowRegex = Regex("(\\d{1,2}:?\\d{2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*(\\d{1,2}:?\\d{2}:\\d{2}[.,]\\d{1,3})")

        while (i < lines.size) {
            val line = lines[i].trim().removePrefix("\uFEFF")
            val match = arrowRegex.find(line)
            if (match != null) {
                val startMs = parseTimestampToMs(match.groupValues[1])
                val endMs = parseTimestampToMs(match.groupValues[2])
                i++
                val textBuilder = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank()) {
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                    textBuilder.append(stripHtmlAndAssTags(lines[i].trim()))
                    i++
                }
                val cleanText = textBuilder.toString().trim()
                if (cleanText.isNotEmpty() && endMs > startMs) {
                    cues.add(TimedSubtitleCue(startMs, endMs, cleanText))
                }
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseAssSsa(lines: List<String>): List<TimedSubtitleCue> {
        val cues = mutableListOf<TimedSubtitleCue>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = line.substringAfter("Dialogue:").trim()
                val parts = payload.split(",", limit = 10)
                if (parts.size >= 10) {
                    val startMs = parseTimestampToMs(parts[1].trim())
                    val endMs = parseTimestampToMs(parts[2].trim())
                    val rawText = parts[9]
                        .replace("\\N", "\n")
                        .replace("\\n", "\n")
                        .replace("\\h", " ")
                    val cleanText = stripHtmlAndAssTags(rawText).trim()
                    if (cleanText.isNotEmpty() && endMs > startMs) {
                        cues.add(TimedSubtitleCue(startMs, endMs, cleanText))
                    }
                }
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseMicroDvdSub(lines: List<String>, fps: Float): List<TimedSubtitleCue> {
        val safeFps = if (fps > 1f) fps else 23.976f
        val regex = Regex("^\\{(\\d+)\\}\\{(\\d+)\\}(.*)$")
        val cues = mutableListOf<TimedSubtitleCue>()
        for (raw in lines) {
            val match = regex.find(raw.trim()) ?: continue
            val startFrame = match.groupValues[1].toLongOrNull() ?: continue
            val endFrame = match.groupValues[2].toLongOrNull() ?: continue
            val text = stripHtmlAndAssTags(match.groupValues[3].replace("|", "\n")).trim()
            val startMs = ((startFrame * 1000f) / safeFps).roundToLong()
            val endMs = ((endFrame * 1000f) / safeFps).roundToLong()
            if (text.isNotEmpty() && endMs > startMs) {
                cues.add(TimedSubtitleCue(startMs, endMs, text))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTtml(lines: List<String>): List<TimedSubtitleCue> {
        val joined = lines.joinToString(" ")
        val pRegex = Regex("<p[^>]*begin=\"([^\"]+)\"[^>]*end=\"([^\"]+)\"[^>]*>(.*?)</p>", RegexOption.IGNORE_CASE)
        val cues = mutableListOf<TimedSubtitleCue>()
        pRegex.findAll(joined).forEach { match ->
            val startMs = parseTimestampToMs(match.groupValues[1])
            val endMs = parseTimestampToMs(match.groupValues[2])
            val text = stripHtmlAndAssTags(
                match.groupValues[3].replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            ).trim()
            if (text.isNotEmpty() && endMs > startMs) {
                cues.add(TimedSubtitleCue(startMs, endMs, text))
            }
        }
        return cues.sortedBy { it.startMs }
    }

    private fun parseTimestampToMs(raw: String): Long {
        val clean = raw.trim().replace(',', '.')
        if (clean.endsWith("s")) {
            val sec = clean.removeSuffix("s").toDoubleOrNull() ?: 0.0
            return (sec * 1000.0).roundToLong()
        }
        val parts = clean.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toDouble()
                    (h * 3600_000L) + (m * 60_000L) + (s * 1000.0).roundToLong()
                }
                2 -> {
                    val m = parts[0].toLong()
                    val s = parts[1].toDouble()
                    (m * 60_000L) + (s * 1000.0).roundToLong()
                }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun stripHtmlAndAssTags(input: String): String {
        return input
            .replace(Regex("\\{\\\\[^}]*\\}"), "")
            .replace(Regex("<[^>]*>"), "")
    }
}

data class PlayerTrackOption(
    val uiIndex: Int,
    val groupIndex: Int,
    val trackIndexInGroup: Int,
    val label: String,
    val language: String,
    val codecBadge: String,
    val detailsBadge: String,
    val isSelected: Boolean
)

data class LiveNerdStats(
    val videoCodec: String = "N/A",
    val resolutionAndFps: String = "N/A",
    val activeDecoderBadge: String = "HW+",
    val hdrSummary: String = "SDR",
    val videoBitrateKbps: Int = 0,
    val droppedFrames: Int = 0,
    val audioCodecAndChannels: String = "N/A",
    val audioSampleRateHz: Int = 0,
    val networkProtocol: String = "Local file"
)

class PlayerViewModel(
    private val appContext: Context,
    private val dao: FlagshipPlayerDao,
    private val prefsRepo: AppPreferencesRepository
) : ViewModel() {

    val preferencesState: StateFlow<AppPreferencesState> = prefsRepo.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferencesState())

    private val delayingAudioProcessor = DelayingAudioProcessor()
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val deviceDisplaySupportsHdr: Boolean = HdrDisplayHelper.doesDeviceDisplaySupportHdr(appContext)

    private val _exoPlayerFlow = MutableStateFlow<ExoPlayer?>(null)
    val exoPlayerFlow: StateFlow<ExoPlayer?> = _exoPlayerFlow.asStateFlow()

    private val _activeVideo = MutableStateFlow<LocalVideoItem?>(null)
    val activeVideo: StateFlow<LocalVideoItem?> = _activeVideo.asStateFlow()

    private val _playlistQueue = MutableStateFlow<List<LocalVideoItem>>(emptyList())
    val hasPreviousVideo: StateFlow<Boolean> = combine(_activeVideo, _playlistQueue) { cur, list ->
        if (cur == null || list.size <= 1) false
        else list.indexOfFirst { it.filePath == cur.filePath } > 0
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val hasNextVideo: StateFlow<Boolean> = combine(_activeVideo, _playlistQueue) { cur, list ->
        if (cur == null || list.size <= 1) false
        else {
            val idx = list.indexOfFirst { it.filePath == cur.filePath }
            idx in 0 until (list.size - 1)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedPercentage = MutableStateFlow(0)
    val bufferedPercentage: StateFlow<Int> = _bufferedPercentage.asStateFlow()

    private val _decoderMode = MutableStateFlow(DecoderMode.HW_PLUS)
    val decoderMode: StateFlow<DecoderMode> = _decoderMode.asStateFlow()

    private val _isCurrentVideoHdr = MutableStateFlow(false)
    val isCurrentVideoHdr: StateFlow<Boolean> = _isCurrentVideoHdr.asStateFlow()

    private val _activeHdrFormatBadge = MutableStateFlow("SDR")
    val activeHdrFormatBadge: StateFlow<String> = _activeHdrFormatBadge.asStateFlow()

    private val _forceSdrToneMapping = MutableStateFlow(false)
    val forceSdrToneMapping: StateFlow<Boolean> = _forceSdrToneMapping.asStateFlow()

    val shouldApplySdrVisualToneMap: StateFlow<Boolean> = combine(
        _isCurrentVideoHdr,
        _forceSdrToneMapping
    ) { isHdr, userForcedSdr ->
        isHdr && (userForcedSdr || !deviceDisplaySupportsHdr)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isHoldFastForwardActive = MutableStateFlow(false)
    val isHoldFastForwardActive: StateFlow<Boolean> = _isHoldFastForwardActive.asStateFlow()

    private val _activeHoldSpeed = MutableStateFlow(2.0f)
    val activeHoldSpeed: StateFlow<Float> = _activeHoldSpeed.asStateFlow()

    private val _zoomScale = MutableStateFlow(1.0f)
    val zoomScale: StateFlow<Float> = _zoomScale.asStateFlow()

    private val _panOffsetX = MutableStateFlow(0f)
    val panOffsetX: StateFlow<Float> = _panOffsetX.asStateFlow()

    private val _panOffsetY = MutableStateFlow(0f)
    val panOffsetY: StateFlow<Float> = _panOffsetY.asStateFlow()

    private val _aspectRatioMode = MutableStateFlow(AspectRatioMode.FIT)
    val aspectRatioMode: StateFlow<AspectRatioMode> = _aspectRatioMode.asStateFlow()

    private val _orientationMode = MutableStateFlow(PlayerOrientationMode.SENSOR_LANDSCAPE)
    val orientationMode: StateFlow<PlayerOrientationMode> = _orientationMode.asStateFlow()

    private val _videoFrameRate = MutableStateFlow(24f)
    val videoFrameRate: StateFlow<Float> = _videoFrameRate.asStateFlow()

    private val _isTouchLocked = MutableStateFlow(false)
    val isTouchLocked: StateFlow<Boolean> = _isTouchLocked.asStateFlow()

    private val _showNerdStats = MutableStateFlow(false)
    val showNerdStats: StateFlow<Boolean> = _showNerdStats.asStateFlow()

    private val _nerdStats = MutableStateFlow(LiveNerdStats())
    val nerdStats: StateFlow<LiveNerdStats> = _nerdStats.asStateFlow()

    private val _volumeFraction = MutableStateFlow(0.75f)
    val volumeFraction: StateFlow<Float> = _volumeFraction.asStateFlow()

    private val _brightnessOverride = MutableStateFlow<Float?>(null)
    val brightnessOverride: StateFlow<Float?> = _brightnessOverride.asStateFlow()

    private val _loopStartMs = MutableStateFlow<Long?>(null)
    val loopStartMs: StateFlow<Long?> = _loopStartMs.asStateFlow()

    private val _loopEndMs = MutableStateFlow<Long?>(null)
    val loopEndMs: StateFlow<Long?> = _loopEndMs.asStateFlow()

    private val _isLoopEditorVisible = MutableStateFlow(false)
    val isLoopEditorVisible: StateFlow<Boolean> = _isLoopEditorVisible.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<PlayerTrackOption>>(emptyList())
    val audioTracks: StateFlow<List<PlayerTrackOption>> = _audioTracks.asStateFlow()

    private val _selectedAudioUiIndex = MutableStateFlow(-1)
    private val _isAudioDisabled = MutableStateFlow(false)
    val isAudioDisabled: StateFlow<Boolean> = _isAudioDisabled.asStateFlow()

    private val _externalAudioUri = MutableStateFlow<Uri?>(null)
    val externalAudioUri: StateFlow<Uri?> = _externalAudioUri.asStateFlow()

    private val _audioDelayMs = MutableStateFlow(0L)
    val audioDelayMs: StateFlow<Long> = _audioDelayMs.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<PlayerTrackOption>>(emptyList())
    val subtitleTracks: StateFlow<List<PlayerTrackOption>> = _subtitleTracks.asStateFlow()

    private val _selectedSubtitleUiIndex = MutableStateFlow(-1)
    private val _externalSubtitleUri = MutableStateFlow<Uri?>(null)
    val externalSubtitleUri: StateFlow<Uri?> = _externalSubtitleUri.asStateFlow()

    private val _externalSubtitleLabel = MutableStateFlow<String?>(null)
    val externalSubtitleLabel: StateFlow<String?> = _externalSubtitleLabel.asStateFlow()

    private val _subtitleDelayMs = MutableStateFlow(0L)
    val subtitleDelayMs: StateFlow<Long> = _subtitleDelayMs.asStateFlow()

    private val _subtitleSpeedFactor = MutableStateFlow(1.0f)
    val subtitleSpeedFactor: StateFlow<Float> = _subtitleSpeedFactor.asStateFlow()

    private val _subtitleAnchorTimeMs = MutableStateFlow(0L)
    val subtitleAnchorTimeMs: StateFlow<Long> = _subtitleAnchorTimeMs.asStateFlow()

    private val _parsedSubtitleCues = MutableStateFlow<List<TimedSubtitleCue>>(emptyList())
    private val _embeddedLiveCues = MutableStateFlow<List<String>>(emptyList())
    private val _subtitlesEnabled = MutableStateFlow(true)
    val subtitlesEnabled: StateFlow<Boolean> = _subtitlesEnabled.asStateFlow()

    private val _activeSubtitleLines = MutableStateFlow<List<String>>(emptyList())
    val activeSubtitleLines: StateFlow<String?> = _activeSubtitleLines.map { list ->
        if (list.isEmpty()) null else list.joinToString("\n")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _toastBanner = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastBanner: SharedFlow<String> = _toastBanner.asSharedFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<BookmarkEntity>> = _activeVideo
        .flatMapLatest { vid ->
            if (vid == null) flowOf(emptyList())
            else dao.observeBookmarks(vid.fileFingerprint)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val savedLoops: StateFlow<List<SavedLoopEntity>> = _activeVideo
        .flatMapLatest { vid ->
            if (vid == null) flowOf(emptyList())
            else dao.observeSavedLoops(vid.fileFingerprint)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var positionPollerJob: Job? = null
    private var lastScrubDispatchMs: Long = 0L
    private var wasPlayingBeforeScrub: Boolean = false
    private var isCurrentlyScrubPaused: Boolean = false
    private var pendingSavedTrackRestoration: Boolean = false

    init {
        syncDeviceMediaVolume()
    }

    private fun syncDeviceMediaVolume() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            _volumeFraction.value = (curVol.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)
        }
    }

    fun readCurrentSystemBrightnessFraction(): Float {
        return runCatching {
            val raw = Settings.System.getInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                160
            )
            (raw.toFloat() / 255f).coerceIn(0.05f, 1.0f)
        }.getOrDefault(0.6f)
    }

    fun loadAndPlayVideo(
        video: LocalVideoItem,
        playlist: List<LocalVideoItem> = listOf(video),
        autoResume: Boolean = true
    ) {
        viewModelScope.launch {
            saveCurrentVideoStateToRoom()
            syncDeviceMediaVolume()
            _brightnessOverride.value = null

            _activeVideo.value = video
            _playlistQueue.value = playlist.ifEmpty { listOf(video) }
            _loopStartMs.value = null
            _loopEndMs.value = null
            _isLoopEditorVisible.value = false
            _isCurrentVideoHdr.value = false
            _activeHdrFormatBadge.value = "SDR"
            _parsedSubtitleCues.value = emptyList()
            _embeddedLiveCues.value = emptyList()
            _externalSubtitleUri.value = null
            _externalSubtitleLabel.value = null
            _externalAudioUri.value = null

            val savedState = dao.getVideoState(video.fileFingerprint)
            val initialDecoder = savedState?.decoderMode?.let {
                runCatching { DecoderMode.valueOf(it) }.getOrNull()
            } ?: preferencesState.value.defaultDecoderMode

            _decoderMode.value = initialDecoder
            _forceSdrToneMapping.value = savedState?.forceSdrToneMapping ?: false
            _playbackSpeed.value = savedState?.playbackSpeed ?: 1.0f
            _zoomScale.value = savedState?.zoomScale ?: 1.0f
            _panOffsetX.value = savedState?.panOffsetX ?: 0f
            _panOffsetY.value = savedState?.panOffsetY ?: 0f
            _aspectRatioMode.value = savedState?.aspectRatioMode?.let {
                runCatching { AspectRatioMode.valueOf(it) }.getOrNull()
            } ?: AspectRatioMode.FIT
            _orientationMode.value = savedState?.orientationMode?.let {
                runCatching { PlayerOrientationMode.valueOf(it) }.getOrNull()
            } ?: PlayerOrientationMode.SENSOR_LANDSCAPE

            _isAudioDisabled.value = savedState?.isAudioDisabled ?: false
            _selectedAudioUiIndex.value = savedState?.audioTrackIndex ?: -1
            _audioDelayMs.value = savedState?.audioDelayMs ?: 0L
            delayingAudioProcessor.setDelayMs(_audioDelayMs.value)

            _subtitlesEnabled.value = savedState?.subtitlesEnabled ?: true
            _selectedSubtitleUiIndex.value = savedState?.subtitleTrackIndex ?: -1
            _subtitleDelayMs.value = savedState?.subtitleDelayMs ?: 0L
            _subtitleSpeedFactor.value = savedState?.subtitleSpeedFactor ?: 1.0f
            _subtitleAnchorTimeMs.value = savedState?.subtitleAnchorTimeMs ?: 0L
            pendingSavedTrackRestoration = true

            val savedSubUriStr = savedState?.externalSubtitleUri
            if (!savedSubUriStr.isNullOrBlank()) {
                val uri = Uri.parse(savedSubUriStr)
                loadExternalSubtitleFile(uri, uri.lastPathSegment ?: "External subtitle", restoreEnable = _subtitlesEnabled.value)
            } else if (video.sidecarSubtitlePaths.isNotEmpty()) {
                val firstPath = video.sidecarSubtitlePaths.first()
                val sidecarFile = File(firstPath)
                if (sidecarFile.exists()) {
                    loadExternalSubtitleFile(Uri.fromFile(sidecarFile), sidecarFile.name, restoreEnable = _subtitlesEnabled.value)
                }
            }

            val savedAudioUriStr = savedState?.externalAudioUri
            if (!savedAudioUriStr.isNullOrBlank()) {
                _externalAudioUri.value = Uri.parse(savedAudioUriStr)
            }

            val startPos = if (autoResume) {
                savedState?.positionMs ?: video.savedPositionMs
            } else 0L

            buildOrRebuildExoPlayer(
                video = video,
                decoder = initialDecoder,
                seekPositionMs = startPos,
                playWhenReady = true
            )
        }
    }

    fun playPreviousVideoInQueue() {
        val cur = _activeVideo.value ?: return
        val queue = _playlistQueue.value
        val idx = queue.indexOfFirst { it.filePath == cur.filePath }
        if (idx > 0) {
            loadAndPlayVideo(queue[idx - 1], queue, autoResume = true)
        }
    }

    fun playNextVideoInQueue() {
        val cur = _activeVideo.value ?: return
        val queue = _playlistQueue.value
        val idx = queue.indexOfFirst { it.filePath == cur.filePath }
        if (idx in 0 until (queue.size - 1)) {
            loadAndPlayVideo(queue[idx + 1], queue, autoResume = true)
        }
    }

    fun toggleForceSdrToneMapping() {
        val next = !_forceSdrToneMapping.value
        _forceSdrToneMapping.value = next
        val video = _activeVideo.value ?: return
        val player = _exoPlayerFlow.value
        val currentPos = player?.currentPosition ?: _currentPositionMs.value
        val wasPlaying = player?.isPlaying ?: true

        pendingSavedTrackRestoration = true
        buildOrRebuildExoPlayer(
            video = video,
            decoder = _decoderMode.value,
            seekPositionMs = currentPos,
            playWhenReady = wasPlaying
        )
        viewModelScope.launch {
            _toastBanner.emit(if (next) "HDR converted to SDR" else "Native HDR active")
            saveCurrentVideoStateToRoom()
        }
    }

    private fun buildOrRebuildExoPlayer(
        video: LocalVideoItem,
        decoder: DecoderMode,
        seekPositionMs: Long,
        playWhenReady: Boolean
    ) {
        releasePlayerInternal()

        val renderersFactory = ConfigurableRenderersFactory(
            context = appContext,
            decoderMode = decoder,
            delayingAudioProcessor = delayingAudioProcessor
        )

        val mediaSourceFactory = buildMediaSourceFactory(video)

        val minBufferMs = if (decoder == DecoderMode.SW) 25_000 else 15_000
        val maxBufferMs = if (decoder == DecoderMode.SW) 65_000 else 50_000
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                1_000,
                2_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()

        player.setPlaybackSpeed(_playbackSpeed.value)

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, _isAudioDisabled.value)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !_subtitlesEnabled.value)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (!isPlaying && !isCurrentlyScrubPaused) {
                    viewModelScope.launch { saveCurrentVideoStateToRoom() }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = player.duration
                if (dur != C.TIME_UNSET && dur > 0L) {
                    _durationMs.value = dur
                }
                refreshTracksAndNerdStats(player)
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshTracksAndNerdStats(player)
                if (pendingSavedTrackRestoration && tracks.groups.isNotEmpty()) {
                    pendingSavedTrackRestoration = false
                    applySavedTrackSelectionsToPlayer(player)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                refreshTracksAndNerdStats(player)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupLoudnessEnhancer(audioSessionId)
            }

            override fun onCues(cueGroup: CueGroup) {
                val lines = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }
                _embeddedLiveCues.value = lines
                if (_externalSubtitleUri.value == null && lines.isNotEmpty()) {
                    val now = player.currentPosition
                    val existing = _parsedSubtitleCues.value
                    if (existing.none { abs(it.startMs - now) < 600L }) {
                        _parsedSubtitleCues.value = (existing + TimedSubtitleCue(
                            startMs = now,
                            endMs = now + 3200L,
                            text = lines.joinToString("\n")
                        )).sortedBy { it.startMs }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                viewModelScope.launch {
                    when (_decoderMode.value) {
                        DecoderMode.HW -> {
                            _toastBanner.emit("Switched to HW+ decoder automatically")
                            selectDecoderMode(DecoderMode.HW_PLUS)
                        }
                        DecoderMode.HW_PLUS -> {
                            _toastBanner.emit("Switched to SW decoder automatically")
                            selectDecoderMode(DecoderMode.SW)
                        }
                        DecoderMode.SW -> {
                            _toastBanner.emit("Playback error: ${error.errorCodeName}")
                        }
                    }
                }
            }
        })

        val baseMediaItem = MediaItem.fromUri(video.contentUri)
        val extAudio = _externalAudioUri.value
        if (extAudio != null) {
            val videoSource = mediaSourceFactory.createMediaSource(baseMediaItem)
            val audioSource = DefaultMediaSourceFactory(appContext).createMediaSource(MediaItem.fromUri(extAudio))
            player.setMediaSource(MergingMediaSource(videoSource, audioSource))
        } else {
            player.setMediaItem(baseMediaItem)
        }

        player.prepare()
        if (seekPositionMs > 0L) {
            player.seekTo(seekPositionMs)
        }
        player.playWhenReady = playWhenReady
        setupLoudnessEnhancer(player.audioSessionId)

        _exoPlayerFlow.value = player
        startHighFrequencyPositionAndLoopMonitor(player)
    }

    private fun applySavedTrackSelectionsToPlayer(player: ExoPlayer) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, _isAudioDisabled.value)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !_subtitlesEnabled.value)

        if (!_isAudioDisabled.value && _selectedAudioUiIndex.value >= 0) {
            val matchAudio = _audioTracks.value.find { it.uiIndex == _selectedAudioUiIndex.value }
            val groups = player.currentTracks.groups
            if (matchAudio != null && matchAudio.groupIndex in groups.indices) {
                builder.setOverrideForType(
                    TrackSelectionOverride(
                        groups[matchAudio.groupIndex].mediaTrackGroup,
                        matchAudio.trackIndexInGroup
                    )
                )
            }
        }

        if (_subtitlesEnabled.value && _externalSubtitleUri.value == null && _selectedSubtitleUiIndex.value >= 0) {
            val matchSub = _subtitleTracks.value.find { it.uiIndex == _selectedSubtitleUiIndex.value }
            val groups = player.currentTracks.groups
            if (matchSub != null && matchSub.groupIndex in groups.indices) {
                builder.setOverrideForType(
                    TrackSelectionOverride(
                        groups[matchSub.groupIndex].mediaTrackGroup,
                        matchSub.trackIndexInGroup
                    )
                )
            }
        }

        player.trackSelectionParameters = builder.build()
    }

    private fun buildMediaSourceFactory(video: LocalVideoItem): MediaSource.Factory {
        if (!video.isNetworkStream) {
            return DefaultMediaSourceFactory(appContext)
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

        val requestProps = HashMap<String, String>()
        requestProps.putAll(video.customHeaders)
        if (video.referer.isNotBlank()) {
            requestProps["Referer"] = video.referer
        }

        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(
                video.userAgent.ifBlank {
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
                }
            )
            .setDefaultRequestProperties(requestProps)

        val upstreamFactory = DefaultDataSource.Factory(appContext, okHttpFactory)
        return DefaultMediaSourceFactory(appContext).setDataSourceFactory(upstreamFactory)
    }

    private fun startHighFrequencyPositionAndLoopMonitor(player: ExoPlayer) {
        positionPollerJob?.cancel()
        positionPollerJob = viewModelScope.launch {
            while (isActive) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration
                if (!isCurrentlyScrubPaused) {
                    _currentPositionMs.value = pos
                }
                if (dur != C.TIME_UNSET && dur > 0L) {
                    _durationMs.value = dur
                }
                _bufferedPercentage.value = player.bufferedPercentage

                val startA = _loopStartMs.value
                val endB = _loopEndMs.value
                if (startA != null && endB != null && endB > startA + 100L) {
                    if (pos >= endB || pos < startA - 250L) {
                        player.setSeekParameters(SeekParameters.EXACT)
                        player.seekTo(startA)
                    }
                }

                val evalPos = _currentPositionMs.value
                if (!_subtitlesEnabled.value) {
                    _activeSubtitleLines.value = emptyList()
                } else {
                    val cues = _parsedSubtitleCues.value
                    val delay = _subtitleDelayMs.value
                    val speed = _subtitleSpeedFactor.value
                    val anchor = _subtitleAnchorTimeMs.value

                    if (cues.isNotEmpty() && (_externalSubtitleUri.value != null || delay != 0L || abs(speed - 1.0f) > 0.0001f)) {
                        _activeSubtitleLines.value = MultiFormatSubtitleEngine.findActiveCuesAtPosition(
                            cues = cues,
                            currentPositionMs = evalPos,
                            anchorTimeMs = anchor,
                            speedFactor = speed,
                            delayMs = delay
                        )
                    } else {
                        _activeSubtitleLines.value = _embeddedLiveCues.value
                    }
                }

                delay(16L)
            }
        }
    }

    fun selectDecoderMode(targetMode: DecoderMode) {
        if (_decoderMode.value == targetMode && _exoPlayerFlow.value != null) return
        val video = _activeVideo.value ?: return
        val player = _exoPlayerFlow.value
        val currentPos = player?.currentPosition ?: _currentPositionMs.value
        val wasPlaying = player?.isPlaying ?: true

        _decoderMode.value = targetMode
        pendingSavedTrackRestoration = true
        buildOrRebuildExoPlayer(
            video = video,
            decoder = targetMode,
            seekPositionMs = currentPos,
            playWhenReady = wasPlaying
        )
        viewModelScope.launch {
            _toastBanner.emit("Decoder: ${targetMode.badge} (${targetMode.description})")
            saveCurrentVideoStateToRoom()
        }
    }

    fun togglePlayPause() {
        val player = _exoPlayerFlow.value ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    fun beginIncrementalSeekSession() {
        val player = _exoPlayerFlow.value ?: return
        if (!isCurrentlyScrubPaused) {
            wasPlayingBeforeScrub = player.isPlaying
            isCurrentlyScrubPaused = true
            player.pause()
        }
    }

    fun updateIncrementalSeekSession(targetMs: Long) {
        val player = _exoPlayerFlow.value ?: return
        val dur = _durationMs.value.coerceAtLeast(1L)
        val clamped = targetMs.coerceIn(0L, dur)
        _currentPositionMs.value = clamped

        val now = System.currentTimeMillis()
        if ((now - lastScrubDispatchMs) >= 35L) {
            lastScrubDispatchMs = now
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            player.seekTo(clamped)
        }
    }

    fun endIncrementalSeekSession(targetMs: Long, exactFrame: Boolean = false) {
        val player = _exoPlayerFlow.value ?: return
        val dur = _durationMs.value.coerceAtLeast(1L)
        val clamped = targetMs.coerceIn(0L, dur)
        _currentPositionMs.value = clamped

        player.setSeekParameters(if (exactFrame) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        player.seekTo(clamped)

        if (isCurrentlyScrubPaused) {
            isCurrentlyScrubPaused = false
            if (wasPlayingBeforeScrub) {
                player.play()
            }
        }
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun seekToFastOrExact(targetMs: Long, exactFrame: Boolean = false, autoPlay: Boolean = false) {
        val player = _exoPlayerFlow.value ?: return
        val dur = _durationMs.value.coerceAtLeast(1L)
        val clamped = targetMs.coerceIn(0L, dur)
        _currentPositionMs.value = clamped
        player.setSeekParameters(
            if (exactFrame) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC
        )
        player.seekTo(clamped)
        if (autoPlay && !player.isPlaying) {
            player.play()
        }
    }

    fun seekRelativeMs(deltaMs: Long) {
        val base = _exoPlayerFlow.value?.currentPosition ?: _currentPositionMs.value
        seekToFastOrExact(base + deltaMs, exactFrame = false)
    }

    fun setNormalPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        _playbackSpeed.value = clamped
        if (!_isHoldFastForwardActive.value) {
            _exoPlayerFlow.value?.setPlaybackSpeed(clamped)
        }
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun startHoldFastForward(initialMultiplier: Float? = null) {
        val speed = (initialMultiplier ?: preferencesState.value.holdFastForwardSpeed).coerceIn(1.25f, 3.5f)
        _isHoldFastForwardActive.value = true
        _activeHoldSpeed.value = speed
        _exoPlayerFlow.value?.setPlaybackSpeed(speed)
    }

    fun updateHoldFastForwardSpeed(multiplier: Float) {
        if (!_isHoldFastForwardActive.value) return
        val clamped = multiplier.coerceIn(1.25f, 3.5f)
        _activeHoldSpeed.value = clamped
        _exoPlayerFlow.value?.setPlaybackSpeed(clamped)
    }

    fun stopHoldFastForward() {
        if (_isHoldFastForwardActive.value) {
            _isHoldFastForwardActive.value = false
            _exoPlayerFlow.value?.setPlaybackSpeed(_playbackSpeed.value)
        }
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        runCatching { loudnessEnhancer?.release() }
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
            runCatching {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    enabled = true
                }
                applyBoostGainFromVolumeFraction(_volumeFraction.value)
            }
        }
    }

    fun setContinuousVolumeFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0.0f, 2.0f)
        _volumeFraction.value = clamped

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val hwFraction = clamped.coerceIn(0.0f, 1.0f)
            val targetIndex = (hwFraction * maxVol).roundToInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        }
        applyBoostGainFromVolumeFraction(clamped)
    }

    private fun applyBoostGainFromVolumeFraction(fraction: Float) {
        val enhancer = loudnessEnhancer ?: return
        runCatching {
            if (fraction > 1.0f) {
                val boostMb = ((fraction - 1.0f) * 1500f).roundToInt().coerceIn(0, 1500)
                if (!enhancer.enabled) enhancer.enabled = true
                enhancer.setTargetGain(boostMb)
            } else {
                enhancer.setTargetGain(0)
            }
        }
    }

    fun setContinuousBrightnessFraction(fraction: Float) {
        _brightnessOverride.value = fraction.coerceIn(-0.5f, 1.0f)
    }

    fun updateZoomAndPan(scale: Float, panX: Float, panY: Float) {
        _zoomScale.value = scale.coerceIn(0.25f, 5.0f)
        _panOffsetX.value = panX
        _panOffsetY.value = panY
    }

    fun resetZoomAndPan() {
        _zoomScale.value = 1.0f
        _panOffsetX.value = 0f
        _panOffsetY.value = 0f
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun cycleAspectRatioMode() {
        val modes = AspectRatioMode.entries
        val next = modes[(_aspectRatioMode.value.ordinal + 1) % modes.size]
        _aspectRatioMode.value = next
        viewModelScope.launch {
            _toastBanner.emit("Aspect ratio: ${next.label}")
            saveCurrentVideoStateToRoom()
        }
    }

    fun togglePortraitLandscape() {
        val next = if (_orientationMode.value == PlayerOrientationMode.PORTRAIT) {
            PlayerOrientationMode.SENSOR_LANDSCAPE
        } else {
            PlayerOrientationMode.PORTRAIT
        }
        _orientationMode.value = next
        viewModelScope.launch {
            saveCurrentVideoStateToRoom()
        }
    }

    fun setOrientationDirectly(mode: PlayerOrientationMode) {
        if (_orientationMode.value == mode) return
        _orientationMode.value = mode
        viewModelScope.launch {
            saveCurrentVideoStateToRoom()
        }
    }

    fun setTouchLocked(locked: Boolean) {
        _isTouchLocked.value = locked
    }

    fun toggleNerdStats() {
        _showNerdStats.value = !_showNerdStats.value
    }

    fun toggleTopLeftQuickSlot(slot: TopLeftQuickSlot) {
        viewModelScope.launch { prefsRepo.toggleTopLeftSlot(slot) }
    }

    fun toggleTopRightQuickSlot(slot: TopRightQuickSlot) {
        viewModelScope.launch { prefsRepo.toggleTopRightSlot(slot) }
    }

    fun toggleLoopEditorOrStartLoop() {
        val now = _exoPlayerFlow.value?.currentPosition ?: _currentPositionMs.value
        if (_loopStartMs.value == null) {
            _loopStartMs.value = now
            _loopEndMs.value = null
            _isLoopEditorVisible.value = true
            viewModelScope.launch {
                _toastBanner.emit("Point A set • Tap Set B in loop bar")
            }
        } else {
            _isLoopEditorVisible.value = !_isLoopEditorVisible.value
        }
    }

    fun setLoopEditorVisible(visible: Boolean) {
        _isLoopEditorVisible.value = visible
    }

    fun setLoopPointANow() {
        val now = _exoPlayerFlow.value?.currentPosition ?: _currentPositionMs.value
        _loopStartMs.value = now
        val endB = _loopEndMs.value
        if (endB != null && endB <= now + 150L) {
            _loopEndMs.value = null
        }
        _isLoopEditorVisible.value = true
    }

    fun setLoopPointBNow() {
        val now = _exoPlayerFlow.value?.currentPosition ?: _currentPositionMs.value
        val startA = _loopStartMs.value ?: 0L
        if (now > startA + 150L) {
            _loopEndMs.value = now
        } else {
            val autoB = (startA + 4000L).coerceAtMost(_durationMs.value.coerceAtLeast(startA + 1000L))
            _loopEndMs.value = autoB
        }
        viewModelScope.launch {
            _toastBanner.emit("A-B loop active")
        }
    }

    fun adjustLoopPointA(deltaMs: Long) {
        val currentA = _loopStartMs.value ?: return
        val maxA = (_loopEndMs.value ?: _durationMs.value) - 200L
        val newA = (currentA + deltaMs).coerceIn(0L, maxA.coerceAtLeast(0L))
        _loopStartMs.value = newA
        seekToFastOrExact(newA, exactFrame = true)
    }

    fun adjustLoopPointB(deltaMs: Long) {
        val currentB = _loopEndMs.value ?: return
        val minB = (_loopStartMs.value ?: 0L) + 200L
        val newB = (currentB + deltaMs).coerceIn(minB, _durationMs.value.coerceAtLeast(minB))
        _loopEndMs.value = newB
    }

    fun setLoopPointsDirect(startMs: Long, endMs: Long) {
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = endMs.coerceAtLeast(safeStart + 200L)
        _loopStartMs.value = safeStart
        _loopEndMs.value = safeEnd
    }

    fun clearAbLoop() {
        _loopStartMs.value = null
        _loopEndMs.value = null
        _isLoopEditorVisible.value = false
        viewModelScope.launch { _toastBanner.emit("Loop exited") }
    }

    fun saveCurrentAbLoop(customLabel: String = "") {
        val vid = _activeVideo.value ?: return
        val startA = _loopStartMs.value ?: return
        val endB = _loopEndMs.value ?: return
        viewModelScope.launch {
            val label = customLabel.ifBlank {
                "Loop ${FormatUtils.formatDurationMs(startA)} - ${FormatUtils.formatDurationMs(endB)}"
            }
            dao.insertSavedLoop(
                SavedLoopEntity(
                    videoFingerprint = vid.fileFingerprint,
                    label = label,
                    startMs = startA,
                    endMs = endB
                )
            )
            _toastBanner.emit("Saved \"$label\" to menu")
        }
    }

    fun activateSavedLoop(loop: SavedLoopEntity) {
        _loopStartMs.value = loop.startMs
        _loopEndMs.value = loop.endMs
        _isLoopEditorVisible.value = false
        seekToFastOrExact(loop.startMs, exactFrame = true, autoPlay = true)
        viewModelScope.launch { _toastBanner.emit("Playing saved loop: ${loop.label}") }
    }

    fun deleteSavedLoop(loop: SavedLoopEntity) {
        viewModelScope.launch { dao.deleteSavedLoop(loop) }
    }

    fun addBookmarkAtCurrentPosition(customLabel: String = "") {
        val vid = _activeVideo.value ?: return
        val pos = _exoPlayerFlow.value?.currentPosition ?: _currentPositionMs.value
        viewModelScope.launch {
            val label = customLabel.ifBlank {
                "Bookmark @ ${FormatUtils.formatPreciseCentiseconds(pos)}"
            }
            dao.insertBookmark(
                BookmarkEntity(
                    videoFingerprint = vid.fileFingerprint,
                    timestampMs = pos,
                    label = label
                )
            )
            _toastBanner.emit("Bookmarked ${FormatUtils.formatDurationMs(pos)}")
        }
    }

    fun jumpToBookmarkAndPlay(bookmark: BookmarkEntity) {
        seekToFastOrExact(bookmark.timestampMs, exactFrame = true, autoPlay = true)
        viewModelScope.launch {
            _toastBanner.emit("Jumped to ${bookmark.label}")
        }
    }

    fun updateBookmarkLabel(bookmark: BookmarkEntity, newLabel: String) {
        viewModelScope.launch {
            dao.updateBookmark(bookmark.copy(label = newLabel.trim().ifBlank { bookmark.label }))
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch { dao.deleteBookmark(bookmark) }
    }

    fun adjustSubtitleDelayMs(deltaMs: Long) {
        _subtitleDelayMs.value = (_subtitleDelayMs.value + deltaMs).coerceIn(-60_000L, 60_000L)
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun setSubtitleDelayDirectMs(exactMs: Long) {
        _subtitleDelayMs.value = exactMs.coerceIn(-60_000L, 60_000L)
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun resetSubtitleDelay() {
        _subtitleDelayMs.value = 0L
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun setSubtitleSpeedFactor(factor: Float) {
        _subtitleSpeedFactor.value = factor.coerceIn(0.8500f, 1.1500f)
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun anchorSubtitleSpeedAtCurrentTime() {
        val pos = _exoPlayerFlow.value?.currentPosition ?: _currentPositionMs.value
        _subtitleAnchorTimeMs.value = pos
        viewModelScope.launch {
            _toastBanner.emit("Subtitle anchor set at ${FormatUtils.formatDurationMs(pos)}")
            saveCurrentVideoStateToRoom()
        }
    }

    fun resetSubtitleSpeedAnchor() {
        _subtitleAnchorTimeMs.value = 0L
        viewModelScope.launch {
            _toastBanner.emit("Subtitle anchor reset to 00:00")
            saveCurrentVideoStateToRoom()
        }
    }

    fun loadExternalSubtitleFile(uri: Uri, displayName: String, restoreEnable: Boolean = true) {
        viewModelScope.launch {
            val cues = MultiFormatSubtitleEngine.parseSubtitleUri(
                context = appContext,
                uri = uri,
                displayNameHint = displayName,
                videoFps = _videoFrameRate.value
            )
            _parsedSubtitleCues.value = cues
            _externalSubtitleUri.value = uri
            _externalSubtitleLabel.value = displayName
            _subtitlesEnabled.value = restoreEnable
            saveCurrentVideoStateToRoom()
        }
    }

    fun loadExternalAudioFile(uri: Uri) {
        val vid = _activeVideo.value ?: return
        _externalAudioUri.value = uri
        _isAudioDisabled.value = false
        val pos = _exoPlayerFlow.value?.currentPosition ?: _currentPositionMs.value
        buildOrRebuildExoPlayer(vid, _decoderMode.value, pos, true)
        viewModelScope.launch {
            _toastBanner.emit("Loaded external audio track")
            saveCurrentVideoStateToRoom()
        }
    }

    fun adjustAudioDelayMs(deltaMs: Long) {
        val next = (_audioDelayMs.value + deltaMs).coerceIn(-5000L, 5000L)
        _audioDelayMs.value = next
        delayingAudioProcessor.setDelayMs(next)
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun resetAudioDelay() {
        _audioDelayMs.value = 0L
        delayingAudioProcessor.setDelayMs(0L)
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun selectAudioTrack(option: PlayerTrackOption?) {
        val player = _exoPlayerFlow.value ?: return
        if (option == null) {
            _isAudioDisabled.value = true
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            refreshTracksAndNerdStats(player)
            viewModelScope.launch {
                _toastBanner.emit("Audio disabled")
                saveCurrentVideoStateToRoom()
            }
            return
        }

        _isAudioDisabled.value = false
        _selectedAudioUiIndex.value = option.uiIndex
        val groups = player.currentTracks.groups
        if (option.groupIndex in groups.indices) {
            val group = groups[option.groupIndex]
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, option.trackIndexInGroup)
                )
                .build()
        }
        refreshTracksAndNerdStats(player)
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun selectSubtitleTrack(option: PlayerTrackOption?) {
        val player = _exoPlayerFlow.value ?: return
        if (option == null) {
            _subtitlesEnabled.value = false
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            refreshTracksAndNerdStats(player)
            viewModelScope.launch { saveCurrentVideoStateToRoom() }
            return
        }
        _subtitlesEnabled.value = true
        _selectedSubtitleUiIndex.value = option.uiIndex
        _externalSubtitleUri.value = null
        _externalSubtitleLabel.value = null
        val groups = player.currentTracks.groups
        if (option.groupIndex in groups.indices) {
            val group = groups[option.groupIndex]
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, option.trackIndexInGroup)
                )
                .build()
        }
        refreshTracksAndNerdStats(player)
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun enableExternalSubtitleAgain() {
        _subtitlesEnabled.value = true
        viewModelScope.launch { saveCurrentVideoStateToRoom() }
    }

    fun updateSubtitleStyle(
        font: SubtitleFontChoice? = null,
        sizeSp: Float? = null,
        colorArgb: Int? = null,
        bgOpacity: Float? = null,
        shadowStrength: Float? = null,
        outlineWidth: Float? = null,
        bold: Boolean? = null,
        bottomMarginDp: Float? = null
    ) {
        viewModelScope.launch {
            prefsRepo.updateSubtitleStyle(
                font = font,
                sizeSp = sizeSp,
                colorArgb = colorArgb,
                bgOpacity = bgOpacity,
                shadowStrength = shadowStrength,
                outlineWidth = outlineWidth,
                bold = bold,
                bottomMarginDp = bottomMarginDp
            )
        }
    }

    private fun refreshTracksAndNerdStats(player: ExoPlayer) {
        val audioList = mutableListOf<PlayerTrackOption>()
        val subList = mutableListOf<PlayerTrackOption>()

        var vCodec = "Unknown"
        var vResFps = "Unknown"
        var vBitrate = 0
        var aCodecChan = if (_isAudioDisabled.value) "Disabled" else "Stereo"
        var aSampleRate = 48000
        var detectedHdr = false
        var detectedHdrBadge = "SDR"

        val groups = player.currentTracks.groups
        var audioUiIdx = 0
        var subUiIdx = 0

        for (gIdx in groups.indices) {
            val group = groups[gIdx]
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    for (tIdx in 0 until group.length) {
                        if (group.isTrackSelected(tIdx)) {
                            val fmt = group.getTrackFormat(tIdx)
                            vCodec = (fmt.sampleMimeType ?: "video/hevc").substringAfter('/').uppercase()
                            val fps = if (fmt.frameRate > 0f) fmt.frameRate else 24f
                            _videoFrameRate.value = fps
                            vResFps = "${fmt.width}x${fmt.height} @ ${String.format(Locale.US, "%.2f", fps)} fps"
                            vBitrate = if (fmt.bitrate > 0) fmt.bitrate / 1000 else 0
                            val (isHdr, hdrLabel) = HdrDisplayHelper.detectHdrBadgeFromColorInfo(
                                fmt.colorInfo,
                                fmt.sampleMimeType
                            )
                            detectedHdr = isHdr
                            detectedHdrBadge = hdrLabel
                        }
                    }
                }
                C.TRACK_TYPE_AUDIO -> {
                    for (tIdx in 0 until group.length) {
                        val fmt = group.getTrackFormat(tIdx)
                        val selected = !_isAudioDisabled.value && group.isTrackSelected(tIdx)
                        val lang = fmt.language?.uppercase() ?: "UND"
                        val mime = (fmt.sampleMimeType ?: "audio/aac").substringAfter('/').uppercase()
                        val ch = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else "2ch"
                        val label = fmt.label ?: "Audio track ${audioUiIdx + 1}"
                        val curIdx = audioUiIdx++
                        audioList.add(
                            PlayerTrackOption(
                                uiIndex = curIdx,
                                groupIndex = gIdx,
                                trackIndexInGroup = tIdx,
                                label = label,
                                language = lang,
                                codecBadge = mime,
                                detailsBadge = "$ch • ${if (fmt.sampleRate > 0) "${fmt.sampleRate / 1000}kHz" else "48kHz"}",
                                isSelected = selected
                            )
                        )
                        if (selected) {
                            _selectedAudioUiIndex.value = curIdx
                            aCodecChan = "$mime ($ch)"
                            if (fmt.sampleRate > 0) aSampleRate = fmt.sampleRate
                        }
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (tIdx in 0 until group.length) {
                        val fmt = group.getTrackFormat(tIdx)
                        val selected = _subtitlesEnabled.value && _externalSubtitleUri.value == null && group.isTrackSelected(tIdx)
                        val lang = fmt.language?.uppercase() ?: "SUB"
                        val mime = (fmt.sampleMimeType ?: "text/srt").substringAfter('/').uppercase()
                        val label = fmt.label ?: "Subtitle ${subUiIdx + 1}"
                        val curIdx = subUiIdx++
                        subList.add(
                            PlayerTrackOption(
                                uiIndex = curIdx,
                                groupIndex = gIdx,
                                trackIndexInGroup = tIdx,
                                label = label,
                                language = lang,
                                codecBadge = mime,
                                detailsBadge = lang,
                                isSelected = selected
                            )
                        )
                        if (selected) {
                            _selectedSubtitleUiIndex.value = curIdx
                        }
                    }
                }
            }
        }

        _isCurrentVideoHdr.value = detectedHdr
        _activeHdrFormatBadge.value = if (detectedHdr && (_forceSdrToneMapping.value || !deviceDisplaySupportsHdr)) {
            "$detectedHdrBadge \u2192 SDR"
        } else {
            detectedHdrBadge
        }
        _audioTracks.value = audioList
        _subtitleTracks.value = subList

        val dropped = player.videoDecoderCounters?.droppedBufferCount ?: 0
        val activeVid = _activeVideo.value
        _nerdStats.value = LiveNerdStats(
            videoCodec = vCodec,
            resolutionAndFps = vResFps,
            activeDecoderBadge = "${_decoderMode.value.badge} (${_decoderMode.value.description})",
            hdrSummary = _activeHdrFormatBadge.value,
            videoBitrateKbps = vBitrate,
            droppedFrames = dropped,
            audioCodecAndChannels = aCodecChan,
            audioSampleRateHz = aSampleRate,
            networkProtocol = if (activeVid?.isNetworkStream == true) activeVid.extensionBadge else "Local storage"
        )
    }

    suspend fun saveCurrentVideoStateToRoom() {
        val vid = _activeVideo.value ?: return
        val player = _exoPlayerFlow.value
        val pos = player?.currentPosition?.coerceAtLeast(0L) ?: _currentPositionMs.value
        val dur = _durationMs.value.coerceAtLeast(vid.durationMs)

        val entity = VideoStateEntity(
            fileFingerprint = vid.fileFingerprint,
            filePath = vid.filePath,
            folderPath = vid.folderPath,
            displayName = vid.displayName,
            positionMs = pos,
            durationMs = dur,
            lastPlayedAtMs = System.currentTimeMillis(),
            playbackSpeed = _playbackSpeed.value,
            decoderMode = _decoderMode.value.name,
            forceSdrToneMapping = _forceSdrToneMapping.value,
            zoomScale = _zoomScale.value,
            panOffsetX = _panOffsetX.value,
            panOffsetY = _panOffsetY.value,
            aspectRatioMode = _aspectRatioMode.value.name,
            orientationMode = _orientationMode.value.name,
            audioTrackIndex = _selectedAudioUiIndex.value,
            isAudioDisabled = _isAudioDisabled.value,
            audioDelayMs = _audioDelayMs.value,
            externalAudioUri = _externalAudioUri.value?.toString(),
            subtitleTrackIndex = _selectedSubtitleUiIndex.value,
            subtitlesEnabled = _subtitlesEnabled.value,
            externalSubtitleUri = _externalSubtitleUri.value?.toString(),
            subtitleDelayMs = _subtitleDelayMs.value,
            subtitleSpeedFactor = _subtitleSpeedFactor.value,
            subtitleAnchorTimeMs = _subtitleAnchorTimeMs.value
        )
        dao.upsertVideoState(entity)
    }

    private fun releasePlayerInternal() {
        positionPollerJob?.cancel()
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        _exoPlayerFlow.value?.release()
        _exoPlayerFlow.value = null
    }

    fun stopAndReleasePlayer() {
        viewModelScope.launch {
            saveCurrentVideoStateToRoom()
            releasePlayerInternal()
            _activeVideo.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayerInternal()
    }

    class Factory(
        private val context: Context,
        private val dao: FlagshipPlayerDao,
        private val prefsRepo: AppPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PlayerViewModel(context.applicationContext, dao, prefsRepo) as T
        }
    }
}
