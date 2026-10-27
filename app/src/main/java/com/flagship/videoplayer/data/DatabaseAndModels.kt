package com.flagship.videoplayer.data

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

enum class AppThemeMode(val label: String) {
    OCEAN("Ocean"),
    FOREST_EMERALD("Emerald forest"),
    ROYAL_VIOLET("Royal violet"),
    BANANA("Banana"),
    TERRACOTTA("Terracotta"),
    SLATE_DUSK("Slate dusk"),
    MIDNIGHT("Midnight")
}

enum class AppIconColor(val label: String, val aliasName: String, val previewHex: Long) {
    BLUE("Ocean blue", "com.flagship.videoplayer.LauncherBlue", 0xFF4D9CFF),
    EMERALD("Emerald green", "com.flagship.videoplayer.LauncherEmerald", 0xFF10B981),
    GOLD("Gold yellow", "com.flagship.videoplayer.LauncherGold", 0xFFFFD83D),
    VIOLET("Royal violet", "com.flagship.videoplayer.LauncherViolet", 0xFFA78BFA),
    ONYX("Onyx cyan", "com.flagship.videoplayer.LauncherOnyx", 0xFF16181E)
}

enum class LibraryViewMode(val label: String) {
    FOLDER("Folders"),
    RICH_LIST("List"),
    COMFORT_GRID("Grid"),
    COMPACT_GRID("Compact")
}

enum class FolderContentViewMode(val label: String) {
    RICH_LIST("List"),
    COMFORT_GRID("Grid"),
    COMPACT_GRID("Compact")
}

enum class LibrarySortField(val label: String) {
    NAME("Title"),
    DATE_MODIFIED("Date added"),
    SIZE("File size"),
    DURATION("Duration"),
    LAST_PLAYED("Last played")
}

enum class DecoderMode(val badge: String, val description: String) {
    HW("HW", "Direct Hardware MediaCodec"),
    HW_PLUS("HW+", "Hybrid Codec + FFmpeg Audio"),
    SW("SW", "Multi-Thread Software & FFmpeg")
}

enum class AspectRatioMode(val label: String) {
    FIT("Fit screen"),
    FILL("Fill / Crop"),
    STRETCH("Stretch"),
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3")
}

enum class PlayerOrientationMode(val label: String) {
    SENSOR_LANDSCAPE("Landscape"),
    PORTRAIT("Portrait"),
    REVERSE_LANDSCAPE("Reverse landscape"),
    MATCH_VIDEO("Match video"),
    AUTO_SYSTEM("Auto rotate")
}

enum class SubtitleFontChoice(val label: String) {
    INTER("Inter (Default)"),
    ROBOTO("Roboto"),
    PLUS_JAKARTA("Plus Jakarta Sans"),
    OPEN_SANS("Open Sans"),
    DM_SANS("DM Sans")
}

enum class TopLeftQuickSlot(val key: String, val label: String) {
    SPEED("SPEED", "Playback speed"),
    BOOKMARK("BOOKMARK", "Bookmark pin"),
    LOOP("LOOP", "A-B loop"),
    ORIENTATION("ORIENTATION", "Portrait / Landscape"),
    SUBTITLES("SUBTITLES", "Subtitles"),
    AUDIO("AUDIO", "Audio tracks"),
    ASPECT("ASPECT", "Aspect ratio")
}

enum class TopRightQuickSlot(val key: String, val label: String) {
    LOCK("LOCK", "Lock controls"),
    DECODER("DECODER", "Decoder selector"),
    PIP("PIP", "Picture-in-Picture"),
    STATS("STATS", "Codec & renderer stats")
}

data class VideoTechnicalMetadata(
    val videoCodec: String,
    val audioCodec: String,
    val bitrateFormatted: String,
    val frameRateFormatted: String,
    val hdrFormatDetails: String,
    val colorSpaceDetails: String,
    val isHdr: Boolean
)

object VideoTechnicalProber {
    suspend fun probeVideoFile(context: Context, video: LocalVideoItem): VideoTechnicalMetadata =
        withContext(Dispatchers.IO) {
            var vCodec = video.extensionBadge
            var aCodec = "None / Unknown"
            var bitrateBps = 0L
            var fps = 0f
            var colorTransfer = 0
            var colorStandard = 0
            var profile = 0

            runCatching {
                val extractor = MediaExtractor()
                try {
                    if (File(video.filePath).exists()) {
                        extractor.setDataSource(video.filePath)
                    } else {
                        extractor.setDataSource(context, video.contentUri, null)
                    }
                    for (i in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(i)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/")) {
                            vCodec = formatMimeToCodecName(mime)
                            if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                bitrateBps = fmt.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                            }
                            if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                fps = fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                            }
                            if (fmt.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                                colorTransfer = fmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                            }
                            if (fmt.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                                colorStandard = fmt.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                            }
                            if (fmt.containsKey(MediaFormat.KEY_PROFILE)) {
                                profile = fmt.getInteger(MediaFormat.KEY_PROFILE)
                            }
                        } else if (mime.startsWith("audio/")) {
                            val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                            aCodec = "${formatMimeToCodecName(mime)} (${ch}ch)"
                        }
                    }
                } finally {
                    extractor.release()
                }
            }

            if (bitrateBps <= 0L) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (File(video.filePath).exists()) {
                            retriever.setDataSource(video.filePath)
                        } else {
                            retriever.setDataSource(context, video.contentUri)
                        }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let {
                            bitrateBps = it
                        }
                    } finally {
                        retriever.release()
                    }
                }
            }

            if (bitrateBps <= 0L && video.durationMs > 0L && video.sizeBytes > 0L) {
                bitrateBps = (video.sizeBytes * 8L * 1000L) / video.durationMs
            }

            val bitrateStr = when {
                bitrateBps >= 1_000_000L -> String.format(Locale.US, "%.2f Mbps", bitrateBps / 1_000_000.0)
                bitrateBps >= 1_000L -> String.format(Locale.US, "%d kbps", bitrateBps / 1_000L)
                else -> "Variable"
            }

            val fpsStr = if (fps > 0f) String.format(Locale.US, "%.2f fps", fps) else "Standard"

            val isDolbyVision = vCodec.contains("Dolby Vision", ignoreCase = true)
            val isPqHdr10 = colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084
            val isHlg = colorTransfer == MediaFormat.COLOR_TRANSFER_HLG
            val isBt2020 = colorStandard == MediaFormat.COLOR_STANDARD_BT2020
            val isHdr = isDolbyVision || isPqHdr10 || isHlg || isBt2020

            val hdrDetails = when {
                isDolbyVision -> "Dolby Vision (10-bit Dynamic HDR)"
                isPqHdr10 && profile >= 4096 -> "HDR10+ / PQ (SMPTE ST 2084 • 10-bit)"
                isPqHdr10 -> "HDR10 (SMPTE ST 2084 PQ • 10-bit)"
                isHlg -> "HLG (Hybrid Log-Gamma HDR • 10-bit)"
                isBt2020 -> "Wide Gamut BT.2020 HDR"
                else -> "SDR (Standard Dynamic Range • Rec.709 8-bit)"
            }

            val colorSpaceStr = when (colorStandard) {
                MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020 Wide Gamut"
                MediaFormat.COLOR_STANDARD_BT709 -> "Rec.709 Standard"
                else -> if (isHdr) "BT.2020" else "Rec.709"
            }

            VideoTechnicalMetadata(
                videoCodec = vCodec,
                audioCodec = aCodec,
                bitrateFormatted = bitrateStr,
                frameRateFormatted = fpsStr,
                hdrFormatDetails = hdrDetails,
                colorSpaceDetails = colorSpaceStr,
                isHdr = isHdr
            )
        }

    private fun formatMimeToCodecName(mime: String): String {
        val lower = mime.lowercase()
        return when {
            lower.contains("hevc") || lower.contains("h265") -> "HEVC / H.265"
            lower.contains("avc") || lower.contains("h264") -> "AVC / H.264"
            lower.contains("dolby-vision") || lower.contains("dvhe") -> "Dolby Vision HEVC"
            lower.contains("av01") || lower.contains("av1") -> "AV1"
            lower.contains("vp9") -> "VP9"
            lower.contains("vp8") -> "VP8"
            lower.contains("mp4a") || lower.contains("aac") -> "AAC"
            lower.contains("ac3") -> "Dolby AC-3"
            lower.contains("eac3") -> "Dolby Digital Plus (E-AC3)"
            lower.contains("flac") -> "FLAC Lossless"
            lower.contains("opus") -> "Opus"
            lower.contains("vorbis") -> "Vorbis"
            lower.contains("dts") -> "DTS Coherent Acoustics"
            else -> mime.substringAfter('/').uppercase()
        }
    }
}

object AppLauncherIconManager {
    fun applyLauncherIcon(context: Context, selectedColor: AppIconColor) {
        val pm = context.packageManager
        AppIconColor.entries.forEach { option ->
            val component = ComponentName(context.packageName, option.aliasName)
            val newState = if (option == selectedColor) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            runCatching {
                pm.setComponentEnabledSetting(
                    component,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}

data class LocalVideoItem(
    val mediaStoreId: Long,
    val contentUri: Uri,
    val filePath: String,
    val folderPath: String,
    val folderName: String,
    val displayName: String,
    val titleWithoutExt: String,
    val extensionBadge: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val resolutionBadge: String,
    val dateModifiedSec: Long,
    val sidecarSubtitlePaths: List<String>,
    val fileFingerprint: String,
    val savedPositionMs: Long = 0L,
    val lastPlayedAtMs: Long = 0L,
    val isNetworkStream: Boolean = false,
    val customHeaders: Map<String, String> = emptyMap(),
    val userAgent: String = "",
    val referer: String = ""
) {
    val completionFraction: Float
        get() = if (durationMs > 0L) (savedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val hasSidecarSubtitles: Boolean
        get() = sidecarSubtitlePaths.isNotEmpty()
}

data class VideoFolderItem(
    val folderPath: String,
    val folderName: String,
    val videos: List<LocalVideoItem>,
    val totalSizeBytes: Long,
    val unplayedCount: Int,
    val lastPlayedVideo: LocalVideoItem?
)

@Entity(tableName = "cached_library_videos")
data class CachedVideoEntity(
    @PrimaryKey val filePath: String,
    val mediaStoreId: Long,
    val contentUriString: String,
    val folderPath: String,
    val folderName: String,
    val displayName: String,
    val titleWithoutExt: String,
    val extensionBadge: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val resolutionBadge: String,
    val dateModifiedSec: Long,
    val sidecarPathsJoined: String,
    val fileFingerprint: String
) {
    fun toLocalVideoItem(savedState: VideoStateEntity?): LocalVideoItem {
        val sidecars = if (sidecarPathsJoined.isBlank()) emptyList() else sidecarPathsJoined.split("|")
        return LocalVideoItem(
            mediaStoreId = mediaStoreId,
            contentUri = Uri.parse(contentUriString),
            filePath = filePath,
            folderPath = folderPath,
            folderName = folderName,
            displayName = displayName,
            titleWithoutExt = titleWithoutExt,
            extensionBadge = extensionBadge,
            sizeBytes = sizeBytes,
            durationMs = if (durationMs > 0L) durationMs else (savedState?.durationMs ?: 0L),
            width = width,
            height = height,
            resolutionBadge = resolutionBadge,
            dateModifiedSec = dateModifiedSec,
            sidecarSubtitlePaths = sidecars,
            fileFingerprint = fileFingerprint,
            savedPositionMs = savedState?.positionMs ?: 0L,
            lastPlayedAtMs = savedState?.lastPlayedAtMs ?: 0L
        )
    }
}

@Entity(tableName = "video_states")
data class VideoStateEntity(
    @PrimaryKey val fileFingerprint: String,
    val filePath: String,
    val folderPath: String,
    val displayName: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastPlayedAtMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val decoderMode: String = DecoderMode.HW_PLUS.name,
    val forceSdrToneMapping: Boolean = false,
    val zoomScale: Float = 1.0f,
    val panOffsetX: Float = 0f,
    val panOffsetY: Float = 0f,
    val aspectRatioMode: String = AspectRatioMode.FIT.name,
    val orientationMode: String = PlayerOrientationMode.SENSOR_LANDSCAPE.name,
    val audioTrackIndex: Int = -1,
    val isAudioDisabled: Boolean = false,
    val externalAudioUri: String? = null,
    val audioDelayMs: Long = 0L,
    val subtitleTrackIndex: Int = -1,
    val subtitlesEnabled: Boolean = true,
    val externalSubtitleUri: String? = null,
    val subtitleDelayMs: Long = 0L,
    val subtitleSpeedFactor: Float = 1.0f,
    val subtitleAnchorTimeMs: Long = 0L
)

@Entity(
    tableName = "video_bookmarks",
    indices = [Index(value = ["videoFingerprint"])]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoFingerprint: String,
    val timestampMs: Long,
    val label: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "saved_ab_loops",
    indices = [Index(value = ["videoFingerprint"])]
)
data class SavedLoopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val videoFingerprint: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "trashed_videos")
data class TrashedVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val fileFingerprint: String,
    val originalPath: String,
    val trashPath: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val trashedAtMs: Long,
    val expiresAtMs: Long
) {
    fun daysRemaining(nowMs: Long = System.currentTimeMillis()): Int {
        val diff = (expiresAtMs - nowMs).coerceAtLeast(0L)
        return ((diff / (24L * 3600_000L)) + 1L).toInt().coerceAtMost(30)
    }
}

@Entity(tableName = "network_stream_history")
data class NetworkStreamHistoryEntity(
    @PrimaryKey val streamUrl: String,
    val title: String,
    val protocolBadge: String,
    val userAgent: String = "",
    val referer: String = "",
    val headersSerialized: String = "",
    val lastPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastPlayedAtMs: Long = System.currentTimeMillis()
) {
    fun parsedHeaders(): Map<String, String> {
        if (headersSerialized.isBlank()) return emptyMap()
        return headersSerialized.lines()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                else null
            }.toMap()
    }

    companion object {
        fun serializeHeaders(headers: Map<String, String>): String {
            return headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }

        fun detectProtocolBadge(url: String): String {
            val lower = url.lowercase()
            return when {
                lower.startsWith("rtsp://") -> "RTSP"
                lower.contains(".m3u8") -> "HLS"
                lower.contains(".mpd") -> "DASH"
                lower.contains(".ism") -> "SMOOTH"
                lower.contains(".mkv") -> "MKV"
                lower.contains(".webm") -> "WEBM"
                lower.startsWith("https://") -> "HTTPS"
                else -> "HTTP"
            }
        }
    }
}

@Dao
interface FlagshipPlayerDao {
    @Query("SELECT * FROM cached_library_videos")
    fun observeCachedVideos(): Flow<List<CachedVideoEntity>>

    @Query("SELECT * FROM cached_library_videos")
    suspend fun getCachedVideosOnce(): List<CachedVideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedVideos(videos: List<CachedVideoEntity>)

    @Query("DELETE FROM cached_library_videos WHERE filePath IN (:paths)")
    suspend fun deleteCachedVideosByPath(paths: List<String>)

    @Query("SELECT * FROM video_states")
    fun observeAllVideoStates(): Flow<List<VideoStateEntity>>

    @Query("SELECT * FROM video_states WHERE fileFingerprint = :fingerprint LIMIT 1")
    suspend fun getVideoState(fingerprint: String): VideoStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVideoState(state: VideoStateEntity)

    @Query("UPDATE video_states SET filePath = :newPath, folderPath = :newFolder, displayName = :newDisplayName WHERE fileFingerprint = :fingerprint")
    suspend fun updateRenamedVideoMetadata(
        fingerprint: String,
        newPath: String,
        newFolder: String,
        newDisplayName: String
    )

    @Query("SELECT * FROM video_bookmarks WHERE videoFingerprint = :fingerprint ORDER BY timestampMs ASC")
    fun observeBookmarks(fingerprint: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Update
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    @Query("SELECT * FROM saved_ab_loops WHERE videoFingerprint = :fingerprint ORDER BY startMs ASC")
    fun observeSavedLoops(fingerprint: String): Flow<List<SavedLoopEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSavedLoop(loop: SavedLoopEntity): Long

    @Delete
    suspend fun deleteSavedLoop(loop: SavedLoopEntity)

    @Query("SELECT * FROM trashed_videos ORDER BY trashedAtMs DESC")
    fun observeTrashedVideos(): Flow<List<TrashedVideoEntity>>

    @Query("SELECT * FROM trashed_videos")
    suspend fun getAllTrashedVideosOnce(): List<TrashedVideoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrashedVideo(item: TrashedVideoEntity)

    @Delete
    suspend fun deleteTrashedVideoRecord(item: TrashedVideoEntity)

    @Query("SELECT * FROM trashed_videos WHERE expiresAtMs < :nowMs")
    suspend fun getExpiredTrashedVideos(nowMs: Long): List<TrashedVideoEntity>

    @Query("SELECT * FROM network_stream_history ORDER BY lastPlayedAtMs DESC")
    fun observeNetworkStreams(): Flow<List<NetworkStreamHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetworkStream(stream: NetworkStreamHistoryEntity)

    @Query("UPDATE network_stream_history SET title = :newTitle WHERE streamUrl = :streamUrl")
    suspend fun renameNetworkStream(streamUrl: String, newTitle: String)

    @Delete
    suspend fun deleteNetworkStream(stream: NetworkStreamHistoryEntity)
}

@Database(
    entities = [
        CachedVideoEntity::class,
        VideoStateEntity::class,
        BookmarkEntity::class,
        SavedLoopEntity::class,
        TrashedVideoEntity::class,
        NetworkStreamHistoryEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class FlagshipDatabase : RoomDatabase() {
    abstract fun dao(): FlagshipPlayerDao

    companion object {
        @Volatile
        private var INSTANCE: FlagshipDatabase? = null

        fun getInstance(context: Context): FlagshipDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FlagshipDatabase::class.java,
                    "vdx_flagship_player_v6.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

object FileFingerprintEngine {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun computeQuickFingerprint(fileName: String, sizeBytes: Long): String {
        val key = "$fileName:$sizeBytes"
        return cache.getOrPut(key) {
            sha256String("vdx_fp:$fileName:$sizeBytes")
        }
    }

    fun computeFingerprint(filePath: String, sizeBytes: Long): String {
        val file = File(filePath)
        return computeQuickFingerprint(file.name, sizeBytes)
    }

    fun computeStreamFingerprint(url: String): String {
        return "stream_" + sha256String(url.trim())
    }

    private fun sha256String(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

object TrashAndRenameManager {
    private const val THIRTY_DAYS_MS = 30L * 24L * 3600L * 1000L

    private fun getTrashVaultDir(context: Context): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val vault = File(baseDir, ".trash_vault")
        if (!vault.exists()) vault.mkdirs()
        return vault
    }

    suspend fun moveVideoToTrash(
        context: Context,
        dao: FlagshipPlayerDao,
        video: LocalVideoItem
    ): Result<TrashedVideoEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceFile = File(video.filePath)
            val vaultDir = getTrashVaultDir(context)
            val now = System.currentTimeMillis()
            val safeTrashName = "${now}_${sourceFile.name}"
            val destFile = File(vaultDir, safeTrashName)

            var moved = false
            if (sourceFile.exists()) {
                moved = sourceFile.renameTo(destFile)
                if (!moved) {
                    FileInputStream(sourceFile).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output, bufferSize = 256 * 1024)
                        }
                    }
                    moved = destFile.exists() && destFile.length() == sourceFile.length()
                    if (moved) {
                        sourceFile.delete()
                        runCatching { context.contentResolver.delete(video.contentUri, null, null) }
                    }
                } else {
                    runCatching { context.contentResolver.delete(video.contentUri, null, null) }
                }
            }

            val finalTrashPath = if (moved) destFile.absolutePath else video.filePath
            val entity = TrashedVideoEntity(
                fileFingerprint = video.fileFingerprint,
                originalPath = video.filePath,
                trashPath = finalTrashPath,
                displayName = video.displayName,
                sizeBytes = video.sizeBytes,
                durationMs = video.durationMs,
                trashedAtMs = now,
                expiresAtMs = now + THIRTY_DAYS_MS
            )
            dao.insertTrashedVideo(entity)
            dao.deleteCachedVideosByPath(listOf(video.filePath))
            entity
        }
    }

    suspend fun restoreFromTrash(
        context: Context,
        dao: FlagshipPlayerDao,
        trashed: TrashedVideoEntity
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val trashFile = File(trashed.trashPath)
            val originalFile = File(trashed.originalPath)
            originalFile.parentFile?.mkdirs()

            if (trashFile.exists() && trashFile.absolutePath != originalFile.absolutePath) {
                val renamed = trashFile.renameTo(originalFile)
                if (!renamed) {
                    FileInputStream(trashFile).use { input ->
                        FileOutputStream(originalFile).use { output ->
                            input.copyTo(output, bufferSize = 256 * 1024)
                        }
                    }
                    trashFile.delete()
                }
            }
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(originalFile.absolutePath),
                null,
                null
            )
            dao.deleteTrashedVideoRecord(trashed)
            true
        }
    }

    suspend fun purgeSingleTrashedVideo(
        dao: FlagshipPlayerDao,
        trashed: TrashedVideoEntity
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val trashFile = File(trashed.trashPath)
            if (trashFile.exists()) trashFile.delete()
        }
        dao.deleteTrashedVideoRecord(trashed)
    }

    suspend fun purgeExpiredTrash(dao: FlagshipPlayerDao) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val expired = dao.getExpiredTrashedVideos(now)
        for (item in expired) {
            purgeSingleTrashedVideo(dao, item)
        }
    }

    suspend fun renameVideoPreservingState(
        context: Context,
        dao: FlagshipPlayerDao,
        video: LocalVideoItem,
        newNameWithoutExt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = newNameWithoutExt.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            require(cleanBase.isNotEmpty()) { "File name cannot be empty" }

            val oldFile = File(video.filePath)
            val ext = oldFile.extension.ifEmpty { video.extensionBadge.lowercase() }
            val newDisplayName = if (ext.isNotEmpty()) "$cleanBase.$ext" else cleanBase
            val parentDir = oldFile.parentFile ?: File(video.folderPath)
            val newFile = File(parentDir, newDisplayName)

            var success = false
            if (oldFile.exists()) {
                success = oldFile.renameTo(newFile)
            }
            if (!success) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, newDisplayName)
                }
                val updatedRows = context.contentResolver.update(video.contentUri, values, null, null)
                success = updatedRows > 0
            }

            dao.updateRenamedVideoMetadata(
                fingerprint = video.fileFingerprint,
                newPath = newFile.absolutePath,
                newFolder = parentDir.absolutePath,
                newDisplayName = newDisplayName
            )
            dao.deleteCachedVideosByPath(listOf(video.filePath))
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(oldFile.absolutePath, newFile.absolutePath),
                null,
                null
            )
            newDisplayName
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vdx_player_prefs_v6")

data class AppPreferencesState(
    val themeMode: AppThemeMode = AppThemeMode.OCEAN,
    val appIconColor: AppIconColor = AppIconColor.BLUE,
    val libraryViewMode: LibraryViewMode = LibraryViewMode.RICH_LIST,
    val folderContentViewMode: FolderContentViewMode = FolderContentViewMode.RICH_LIST,
    val sortField: LibrarySortField = LibrarySortField.DATE_MODIFIED,
    val sortAscending: Boolean = false,
    val defaultDecoderMode: DecoderMode = DecoderMode.HW_PLUS,
    val holdFastForwardSpeed: Float = 2.0f,
    val skipIntervalSeconds: Int = 10,
    val autoPipEnabled: Boolean = true,
    val backgroundAudioEnabled: Boolean = false,
    val topLeftSlotsCsv: String = "SPEED,BOOKMARK,LOOP,ORIENTATION",
    val topRightSlotsCsv: String = "LOCK,DECODER",
    val subtitleFont: SubtitleFontChoice = SubtitleFontChoice.INTER,
    val subtitleFontSizeSp: Float = 22f,
    val subtitleTextColorArgb: Int = 0xFFFFFFFF.toInt(),
    val subtitleBgOpacity: Float = 0.25f,
    val subtitleDropShadowStrength: Float = 0.70f,
    val subtitleOutlineWidth: Float = 0.55f,
    val subtitleBold: Boolean = true,
    val subtitleBottomMarginDp: Float = 28f
) {
    val enabledTopLeftSlots: List<TopLeftQuickSlot>
        get() = topLeftSlotsCsv.split(",")
            .mapNotNull { token -> TopLeftQuickSlot.entries.find { it.key == token.trim() } }

    val enabledTopRightSlots: List<TopRightQuickSlot>
        get() = topRightSlotsCsv.split(",")
            .mapNotNull { token -> TopRightQuickSlot.entries.find { it.key == token.trim() } }
}

class AppPreferencesRepository(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode_v6")
        val APP_ICON_COLOR = stringPreferencesKey("app_icon_color_v6")
        val VIEW_MODE = stringPreferencesKey("library_view_mode")
        val FOLDER_CONTENT_VIEW_MODE = stringPreferencesKey("folder_content_view_mode")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ASC = booleanPreferencesKey("sort_ascending")
        val DEFAULT_DECODER = stringPreferencesKey("default_decoder")
        val HOLD_FF_SPEED = floatPreferencesKey("hold_ff_speed")
        val SKIP_INTERVAL_SEC = intPreferencesKey("skip_interval_sec")
        val AUTO_PIP = booleanPreferencesKey("auto_pip")
        val BG_AUDIO = booleanPreferencesKey("bg_audio")
        val TOP_LEFT_SLOTS = stringPreferencesKey("top_left_slots_csv")
        val TOP_RIGHT_SLOTS = stringPreferencesKey("top_right_slots_csv")
        val SUB_FONT = stringPreferencesKey("sub_font_v6")
        val SUB_SIZE_SP = floatPreferencesKey("sub_size_sp")
        val SUB_COLOR = intPreferencesKey("sub_color_argb_v6")
        val SUB_BG_OPACITY = floatPreferencesKey("sub_bg_opacity")
        val SUB_SHADOW_STRENGTH = floatPreferencesKey("sub_shadow_strength_v6")
        val SUB_OUTLINE_WIDTH = floatPreferencesKey("sub_outline_width_v6")
        val SUB_BOLD = booleanPreferencesKey("sub_bold")
        val SUB_BOTTOM_MARGIN = floatPreferencesKey("sub_bottom_margin")
    }

    val preferencesFlow: Flow<AppPreferencesState> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppPreferencesState(
                themeMode = runCatching {
                    AppThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: AppThemeMode.OCEAN.name)
                }.getOrDefault(AppThemeMode.OCEAN),
                appIconColor = runCatching {
                    AppIconColor.valueOf(prefs[Keys.APP_ICON_COLOR] ?: AppIconColor.BLUE.name)
                }.getOrDefault(AppIconColor.BLUE),
                libraryViewMode = runCatching {
                    LibraryViewMode.valueOf(prefs[Keys.VIEW_MODE] ?: LibraryViewMode.RICH_LIST.name)
                }.getOrDefault(LibraryViewMode.RICH_LIST),
                folderContentViewMode = runCatching {
                    FolderContentViewMode.valueOf(prefs[Keys.FOLDER_CONTENT_VIEW_MODE] ?: FolderContentViewMode.RICH_LIST.name)
                }.getOrDefault(FolderContentViewMode.RICH_LIST),
                sortField = runCatching {
                    LibrarySortField.valueOf(prefs[Keys.SORT_FIELD] ?: LibrarySortField.DATE_MODIFIED.name)
                }.getOrDefault(LibrarySortField.DATE_MODIFIED),
                sortAscending = prefs[Keys.SORT_ASC] ?: false,
                defaultDecoderMode = runCatching {
                    DecoderMode.valueOf(prefs[Keys.DEFAULT_DECODER] ?: DecoderMode.HW_PLUS.name)
                }.getOrDefault(DecoderMode.HW_PLUS),
                holdFastForwardSpeed = (prefs[Keys.HOLD_FF_SPEED] ?: 2.0f).coerceIn(1.25f, 3.5f),
                skipIntervalSeconds = (prefs[Keys.SKIP_INTERVAL_SEC] ?: 10).coerceIn(3, 30),
                autoPipEnabled = prefs[Keys.AUTO_PIP] ?: true,
                backgroundAudioEnabled = prefs[Keys.BG_AUDIO] ?: false,
                topLeftSlotsCsv = prefs[Keys.TOP_LEFT_SLOTS] ?: "SPEED,BOOKMARK,LOOP,ORIENTATION",
                topRightSlotsCsv = prefs[Keys.TOP_RIGHT_SLOTS] ?: "LOCK,DECODER",
                subtitleFont = runCatching {
                    SubtitleFontChoice.valueOf(prefs[Keys.SUB_FONT] ?: SubtitleFontChoice.INTER.name)
                }.getOrDefault(SubtitleFontChoice.INTER),
                subtitleFontSizeSp = prefs[Keys.SUB_SIZE_SP] ?: 22f,
                subtitleTextColorArgb = prefs[Keys.SUB_COLOR] ?: 0xFFFFFFFF.toInt(),
                subtitleBgOpacity = prefs[Keys.SUB_BG_OPACITY] ?: 0.25f,
                subtitleDropShadowStrength = prefs[Keys.SUB_SHADOW_STRENGTH] ?: 0.70f,
                subtitleOutlineWidth = prefs[Keys.SUB_OUTLINE_WIDTH] ?: 0.55f,
                subtitleBold = prefs[Keys.SUB_BOLD] ?: true,
                subtitleBottomMarginDp = prefs[Keys.SUB_BOTTOM_MARGIN] ?: 28f
            )
        }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAppIconColor(iconColor: AppIconColor) {
        context.dataStore.edit { it[Keys.APP_ICON_COLOR] = iconColor.name }
        AppLauncherIconManager.applyLauncherIcon(context, iconColor)
    }

    suspend fun setLibraryViewMode(mode: LibraryViewMode) {
        context.dataStore.edit { it[Keys.VIEW_MODE] = mode.name }
    }

    suspend fun setFolderContentViewMode(mode: FolderContentViewMode) {
        context.dataStore.edit { it[Keys.FOLDER_CONTENT_VIEW_MODE] = mode.name }
    }

    suspend fun setSortOption(field: LibrarySortField, ascending: Boolean) {
        context.dataStore.edit {
            it[Keys.SORT_FIELD] = field.name
            it[Keys.SORT_ASC] = ascending
        }
    }

    suspend fun setDefaultDecoderMode(mode: DecoderMode) {
        context.dataStore.edit { it[Keys.DEFAULT_DECODER] = mode.name }
    }

    suspend fun setHoldFastForwardSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.HOLD_FF_SPEED] = speed.coerceIn(1.25f, 3.5f) }
    }

    suspend fun setSkipIntervalSeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.SKIP_INTERVAL_SEC] = seconds.coerceIn(3, 30) }
    }

    suspend fun setAutoPipEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_PIP] = enabled }
    }

    suspend fun setBackgroundAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BG_AUDIO] = enabled }
    }

    suspend fun toggleTopLeftSlot(slot: TopLeftQuickSlot) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.TOP_LEFT_SLOTS] ?: "SPEED,BOOKMARK,LOOP,ORIENTATION")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            if (slot.key in current) {
                current.remove(slot.key)
            } else {
                current.add(slot.key)
            }
            prefs[Keys.TOP_LEFT_SLOTS] = current.joinToString(",")
        }
    }

    suspend fun toggleTopRightSlot(slot: TopRightQuickSlot) {
        context.dataStore.edit { prefs ->
            val current = (prefs[Keys.TOP_RIGHT_SLOTS] ?: "LOCK,DECODER")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            if (slot.key in current) {
                current.remove(slot.key)
            } else {
                current.add(slot.key)
            }
            prefs[Keys.TOP_RIGHT_SLOTS] = current.joinToString(",")
        }
    }

    suspend fun updateSubtitleStyle(
        font: SubtitleFontChoice? = null,
        sizeSp: Float? = null,
        colorArgb: Int? = null,
        bgOpacity: Float? = null,
        shadowStrength: Float? = null,
        outlineWidth: Float? = null,
        bold: Boolean? = null,
        bottomMarginDp: Float? = null
    ) {
        context.dataStore.edit { prefs ->
            font?.let { prefs[Keys.SUB_FONT] = it.name }
            sizeSp?.let { prefs[Keys.SUB_SIZE_SP] = it.coerceIn(14f, 42f) }
            colorArgb?.let { prefs[Keys.SUB_COLOR] = it }
            bgOpacity?.let { prefs[Keys.SUB_BG_OPACITY] = it.coerceIn(0f, 1f) }
            shadowStrength?.let { prefs[Keys.SUB_SHADOW_STRENGTH] = it.coerceIn(0f, 1f) }
            outlineWidth?.let { prefs[Keys.SUB_OUTLINE_WIDTH] = it.coerceIn(0f, 1.5f) }
            bold?.let { prefs[Keys.SUB_BOLD] = it }
            bottomMarginDp?.let { prefs[Keys.SUB_BOTTOM_MARGIN] = it.coerceIn(8f, 120f) }
        }
    }
}
