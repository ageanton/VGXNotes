package com.flagship.videoplayer.ui.home

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flagship.videoplayer.data.*
import com.flagship.videoplayer.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.roundToInt

object FastThumbnailCacheEngine {
    private val memoryLru = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(16 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun getFromMemory(fingerprint: String): Bitmap? = memoryLru.get(fingerprint)

    private fun getDiskThumbFile(context: Context, fingerprint: String): File {
        val dir = File(context.cacheDir, "vdx_fast_thumbs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$fingerprint.jpg")
    }

    suspend fun loadOrGenerateThumbnail(
        context: Context,
        video: LocalVideoItem
    ): Bitmap? = withContext(Dispatchers.IO) {
        memoryLru.get(video.fileFingerprint)?.let { return@withContext it }

        val diskFile = getDiskThumbFile(context, video.fileFingerprint)
        if (diskFile.exists() && diskFile.length() > 100L) {
            val decoded = android.graphics.BitmapFactory.decodeFile(diskFile.absolutePath)
            if (decoded != null) {
                memoryLru.put(video.fileFingerprint, decoded)
                return@withContext decoded
            }
        }

        val loaded: Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(video.contentUri, Size(320, 180), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver,
                    video.mediaStoreId,
                    MediaStore.Video.Thumbnails.MINI_KIND,
                    null
                )
            }
        }.getOrNull()

        if (loaded != null) {
            memoryLru.put(video.fileFingerprint, loaded)
            runCatching {
                FileOutputStream(diskFile).use { out ->
                    loaded.compress(Bitmap.CompressFormat.JPEG, 82, out)
                }
            }
        }
        loaded
    }
}

@Composable
fun InstantVideoThumbnail(
    video: LocalVideoItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(video.fileFingerprint) {
        mutableStateOf(FastThumbnailCacheEngine.getFromMemory(video.fileFingerprint))
    }

    LaunchedEffect(video.fileFingerprint) {
        if (bitmap == null) {
            bitmap = FastThumbnailCacheEngine.loadOrGenerateThumbnail(context, video)
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = video.displayName,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier)
    }
}

object MediaStoreVideoScanner {
    private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "ttml")

    suspend fun syncDeviceVideosToCache(
        context: Context,
        dao: FlagshipPlayerDao
    ) = withContext(Dispatchers.IO) {
        val trashedPaths = dao.getAllTrashedVideosOnce().map { it.originalPath }.toSet()
        val existingCached = dao.getCachedVideosOnce().associateBy { it.filePath }
        val scannedEntities = mutableListOf<CachedVideoEntity>()
        val seenPaths = HashSet<String>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        val folderSubtitleCache = HashMap<String, List<File>>()

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val filePath = cursor.getString(dataCol) ?: continue
                    if (filePath.isBlank() || filePath in trashedPaths || filePath.contains("/.trash_vault/")) {
                        continue
                    }
                    val sizeBytes = cursor.getLong(sizeCol)
                    val dateModifiedSec = cursor.getLong(dateCol)
                    seenPaths.add(filePath)

                    val prev = existingCached[filePath]
                    if (prev != null && prev.sizeBytes == sizeBytes && prev.dateModifiedSec == dateModifiedSec) {
                        scannedEntities.add(prev)
                        continue
                    }

                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: "Video_$id.mp4"
                    val durationMs = cursor.getLong(durCol)
                    val width = cursor.getInt(widthCol)
                    val height = cursor.getInt(heightCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)

                    val fileObj = File(filePath)
                    val parentDir = fileObj.parentFile
                    val folderPath = parentDir?.absolutePath ?: "/storage/emulated/0"
                    val folderName = parentDir?.name?.ifBlank { "Internal storage" } ?: "Videos"

                    val dotIdx = displayName.lastIndexOf('.')
                    val titleWithoutExt = if (dotIdx > 0) displayName.substring(0, dotIdx) else displayName
                    val extBadge = if (dotIdx > 0 && dotIdx < displayName.length - 1) {
                        displayName.substring(dotIdx + 1).uppercase()
                    } else "MP4"

                    val allSubsInFolder = folderSubtitleCache.getOrPut(folderPath) {
                        parentDir?.listFiles { f ->
                            f.isFile && f.extension.lowercase() in SUBTITLE_EXTENSIONS
                        }?.toList() ?: emptyList()
                    }
                    val matchingSidecars = allSubsInFolder.filter { sub ->
                        sub.nameWithoutExtension.equals(titleWithoutExt, ignoreCase = true) ||
                            sub.nameWithoutExtension.startsWith("$titleWithoutExt.", ignoreCase = true)
                    }.map { it.absolutePath }

                    val fingerprint = FileFingerprintEngine.computeQuickFingerprint(displayName, sizeBytes)

                    scannedEntities.add(
                        CachedVideoEntity(
                            filePath = filePath,
                            mediaStoreId = id,
                            contentUriString = contentUri.toString(),
                            folderPath = folderPath,
                            folderName = folderName,
                            displayName = displayName,
                            titleWithoutExt = titleWithoutExt,
                            extensionBadge = extBadge,
                            sizeBytes = sizeBytes,
                            durationMs = durationMs,
                            width = width,
                            height = height,
                            resolutionBadge = FormatUtils.computeResolutionBadge(width, height),
                            dateModifiedSec = dateModifiedSec,
                            sidecarPathsJoined = matchingSidecars.joinToString("|"),
                            fileFingerprint = fingerprint
                        )
                    )
                }
            }
        }

        val removedPaths = existingCached.keys.filter { it !in seenPaths }
        if (removedPaths.isNotEmpty()) {
            dao.deleteCachedVideosByPath(removedPaths)
        }
        if (scannedEntities.isNotEmpty()) {
            dao.upsertCachedVideos(scannedEntities)
        }
    }
}

class HomeLibraryViewModel(
    private val appContext: Context,
    private val dao: FlagshipPlayerDao,
    private val prefsRepo: AppPreferencesRepository
) : ViewModel() {

    val preferencesState: StateFlow<AppPreferencesState> = prefsRepo.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferencesState())

    val trashedVideos: StateFlow<List<TrashedVideoEntity>> = dao.observeTrashedVideos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val networkStreams: StateFlow<List<NetworkStreamHistoryEntity>> = dao.observeNetworkStreams()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allVideoStates: StateFlow<List<VideoStateEntity>> = dao.observeAllVideoStates()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val cachedVideosFlow: StateFlow<List<CachedVideoEntity>> = dao.observeCachedVideos()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFolderPath = MutableStateFlow<String?>(null)
    val selectedFolderPath: StateFlow<String?> = _selectedFolderPath.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private val mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            refreshLibrarySilently()
        }
    }

    init {
        viewModelScope.launch {
            TrashAndRenameManager.purgeExpiredTrash(dao)
        }
        runCatching {
            appContext.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaStoreObserver
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { appContext.contentResolver.unregisterContentObserver(mediaStoreObserver) }
    }

    val enrichedVideos: StateFlow<List<LocalVideoItem>> = combine(
        cachedVideosFlow,
        allVideoStates,
        trashedVideos,
        preferencesState,
        _searchQuery
    ) { cachedList, states, trashed, prefs, query ->
        val trashedSet = trashed.map { it.originalPath }.toSet()
        val stateMap = states.associateBy { it.fileFingerprint }

        val merged = cachedList
            .asSequence()
            .filter { it.filePath !in trashedSet }
            .map { entity -> entity.toLocalVideoItem(stateMap[entity.fileFingerprint]) }
            .filter { item ->
                if (query.isBlank()) true
                else item.displayName.contains(query, ignoreCase = true) ||
                    item.folderName.contains(query, ignoreCase = true) ||
                    item.resolutionBadge.contains(query, ignoreCase = true)
            }
            .toList()

        val comparator = when (prefs.sortField) {
            LibrarySortField.NAME -> compareBy<LocalVideoItem> { it.titleWithoutExt.lowercase() }
            LibrarySortField.DATE_MODIFIED -> compareBy { it.dateModifiedSec }
            LibrarySortField.SIZE -> compareBy { it.sizeBytes }
            LibrarySortField.DURATION -> compareBy { it.durationMs }
            LibrarySortField.LAST_PLAYED -> compareBy { it.lastPlayedAtMs }
        }
        if (prefs.sortAscending) merged.sortedWith(comparator)
        else merged.sortedWith(comparator.reversed())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folders: StateFlow<List<VideoFolderItem>> = enrichedVideos.map { videos ->
        videos.groupBy { it.folderPath }.map { (path, group) ->
            VideoFolderItem(
                folderPath = path,
                folderName = group.firstOrNull()?.folderName ?: "Videos",
                videos = group,
                totalSizeBytes = group.sumOf { it.sizeBytes },
                unplayedCount = group.count { it.lastPlayedAtMs == 0L },
                lastPlayedVideo = group.filter { it.lastPlayedAtMs > 0L }.maxByOrNull { it.lastPlayedAtMs }
            )
        }.sortedBy { it.folderName.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val contextAwareResumeTarget: StateFlow<LocalVideoItem?> = combine(
        enrichedVideos,
        _selectedFolderPath
    ) { videos, activeFolder ->
        val pool = if (activeFolder != null) {
            videos.filter { it.folderPath == activeFolder }
        } else {
            videos
        }
        pool.filter { it.lastPlayedAtMs > 0L }.maxByOrNull { it.lastPlayedAtMs }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun refreshLibrarySilently() {
        viewModelScope.launch {
            MediaStoreVideoScanner.syncDeviceVideosToCache(appContext, dao)
            val snapshot = enrichedVideos.value
            withContext(Dispatchers.IO) {
                for (vid in snapshot.take(80)) {
                    if (FastThumbnailCacheEngine.getFromMemory(vid.fileFingerprint) == null) {
                        FastThumbnailCacheEngine.loadOrGenerateThumbnail(appContext, vid)
                    }
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun openFolder(folderPath: String?) {
        _selectedFolderPath.value = folderPath
    }

    fun setViewMode(mode: LibraryViewMode) {
        viewModelScope.launch { prefsRepo.setLibraryViewMode(mode) }
    }

    fun setFolderContentViewMode(mode: FolderContentViewMode) {
        viewModelScope.launch { prefsRepo.setFolderContentViewMode(mode) }
    }

    fun setSortOption(field: LibrarySortField, ascending: Boolean) {
        viewModelScope.launch { prefsRepo.setSortOption(field, ascending) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { prefsRepo.setThemeMode(mode) }
    }

    fun setAppIconColor(iconColor: AppIconColor) {
        viewModelScope.launch {
            prefsRepo.setAppIconColor(iconColor)
            _snackbarMessage.emit("App icon updated to ${iconColor.label}")
        }
    }

    fun setDefaultDecoder(mode: DecoderMode) {
        viewModelScope.launch { prefsRepo.setDefaultDecoderMode(mode) }
    }

    fun setHoldFastForwardSpeed(speed: Float) {
        viewModelScope.launch { prefsRepo.setHoldFastForwardSpeed(speed) }
    }

    fun setSkipIntervalSeconds(sec: Int) {
        viewModelScope.launch { prefsRepo.setSkipIntervalSeconds(sec) }
    }

    fun setAutoPip(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setAutoPipEnabled(enabled) }
    }

    fun setBackgroundAudio(enabled: Boolean) {
        viewModelScope.launch { prefsRepo.setBackgroundAudioEnabled(enabled) }
    }

    fun renameVideo(video: LocalVideoItem, newTitleWithoutExt: String) {
        viewModelScope.launch {
            TrashAndRenameManager.renameVideoPreservingState(
                context = appContext,
                dao = dao,
                video = video,
                newNameWithoutExt = newTitleWithoutExt
            ).onSuccess { newName ->
                _snackbarMessage.emit("Renamed to $newName")
                refreshLibrarySilently()
            }.onFailure { err ->
                _snackbarMessage.emit("Rename failed: ${err.localizedMessage ?: "Permission denied"}")
            }
        }
    }

    fun moveVideoToTrash(video: LocalVideoItem) {
        viewModelScope.launch {
            TrashAndRenameManager.moveVideoToTrash(appContext, dao, video)
                .onSuccess {
                    _snackbarMessage.emit("Moved \"${video.displayName}\" to Trash")
                    refreshLibrarySilently()
                }
                .onFailure { err ->
                    _snackbarMessage.emit("Could not move to Trash: ${err.localizedMessage}")
                }
        }
    }

    fun restoreTrashedVideo(item: TrashedVideoEntity) {
        viewModelScope.launch {
            TrashAndRenameManager.restoreFromTrash(appContext, dao, item)
                .onSuccess {
                    _snackbarMessage.emit("Restored \"${item.displayName}\"")
                    refreshLibrarySilently()
                }
        }
    }

    fun deleteTrashedVideoForever(item: TrashedVideoEntity) {
        viewModelScope.launch {
            TrashAndRenameManager.purgeSingleTrashedVideo(dao, item)
            _snackbarMessage.emit("Permanently deleted \"${item.displayName}\"")
        }
    }

    fun emptyEntireTrash() {
        viewModelScope.launch {
            val all = dao.getAllTrashedVideosOnce()
            for (item in all) {
                TrashAndRenameManager.purgeSingleTrashedVideo(dao, item)
            }
            _snackbarMessage.emit("Emptied Recycle bin")
        }
    }

    fun saveAndLaunchNetworkStream(
        url: String,
        title: String = "",
        onReadyToPlay: (LocalVideoItem) -> Unit
    ) {
        viewModelScope.launch {
            val cleanUrl = url.trim()
            val existing = networkStreams.value.find { it.streamUrl == cleanUrl }
            val resolvedTitle = title.trim().ifEmpty {
                existing?.title?.takeIf { it.isNotBlank() }
                    ?: cleanUrl.substringAfterLast('/').substringBefore('?').ifBlank { "Network stream" }
            }
            val badge = NetworkStreamHistoryEntity.detectProtocolBadge(cleanUrl)
            val historyEntity = NetworkStreamHistoryEntity(
                streamUrl = cleanUrl,
                title = resolvedTitle,
                protocolBadge = badge,
                lastPlayedAtMs = System.currentTimeMillis()
            )
            dao.upsertNetworkStream(historyEntity)

            val fingerprint = FileFingerprintEngine.computeStreamFingerprint(cleanUrl)
            val savedState = dao.getVideoState(fingerprint)

            val streamVideoItem = LocalVideoItem(
                mediaStoreId = -1L,
                contentUri = Uri.parse(cleanUrl),
                filePath = cleanUrl,
                folderPath = "network://streams",
                folderName = "Network streams",
                displayName = resolvedTitle,
                titleWithoutExt = resolvedTitle,
                extensionBadge = badge,
                sizeBytes = 0L,
                durationMs = savedState?.durationMs ?: 0L,
                width = 1920,
                height = 1080,
                resolutionBadge = badge,
                dateModifiedSec = System.currentTimeMillis() / 1000L,
                sidecarSubtitlePaths = emptyList(),
                fileFingerprint = fingerprint,
                savedPositionMs = savedState?.positionMs ?: 0L,
                lastPlayedAtMs = System.currentTimeMillis(),
                isNetworkStream = true
            )
            onReadyToPlay(streamVideoItem)
        }
    }

    fun renameStreamHistoryItem(item: NetworkStreamHistoryEntity, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            dao.renameNetworkStream(item.streamUrl, clean)
            _snackbarMessage.emit("Stream renamed to \"$clean\"")
        }
    }

    fun deleteStreamHistoryItem(item: NetworkStreamHistoryEntity) {
        viewModelScope.launch { dao.deleteNetworkStream(item) }
    }

    class Factory(
        private val context: Context,
        private val dao: FlagshipPlayerDao,
        private val prefsRepo: AppPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeLibraryViewModel(context.applicationContext, dao, prefsRepo) as T
        }
    }
}

@Composable
private fun NeoBrutalistTopHeader(
    videoCount: Int,
    folderCount: Int,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedViewMode: LibraryViewMode,
    isInsideFolder: Boolean,
    onSelectViewMode: (LibraryViewMode) -> Unit,
    onOpenCloud: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSort: () -> Unit
) {
    val tokens = LocalVdxTokens.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.paper)
            .statusBarsPadding()
            .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // BLOCK A: STATS BAR
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = tokens.tickerBg,
            border = BorderStroke(2.dp, tokens.ink),
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val videoWord = if (videoCount == 1) "1 video" else "$videoCount videos"
                val folderWord = if (folderCount == 1) "1 folder" else "$folderCount folders"

                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = tokens.primary, fontWeight = FontWeight.Bold)) {
                            append(videoWord)
                        }
                        withStyle(SpanStyle(color = tokens.tickerSlash, fontWeight = FontWeight.Normal)) {
                            append(" / ")
                        }
                        withStyle(SpanStyle(color = tokens.secondary, fontWeight = FontWeight.Bold)) {
                            append(folderWord)
                        }
                    },
                    fontFamily = SpaceMonoFontFamily,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(8.dp))

                NeoHardShadowButton(
                    visualWidth = 40.dp,
                    visualHeight = 40.dp,
                    cornerRadius = 10.dp,
                    fillColor = tokens.primary,
                    borderColor = tokens.onTicker,
                    shadowColor = tokens.onTicker,
                    shadowOffset = 3.dp,
                    contentDescription = "Cloud",
                    onClick = onOpenCloud
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudQueue,
                        contentDescription = null,
                        tint = tokens.onColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                NeoHardShadowButton(
                    visualWidth = 40.dp,
                    visualHeight = 40.dp,
                    cornerRadius = 10.dp,
                    fillColor = tokens.primary,
                    borderColor = tokens.onTicker,
                    shadowColor = tokens.onTicker,
                    shadowOffset = 3.dp,
                    contentDescription = "Settings",
                    onClick = onOpenSettings
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        tint = tokens.onColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // BLOCK B: SEARCH ROW
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .offset(x = 4.dp, y = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.ink)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tokens.card)
                        .border(2.dp, tokens.ink, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = tokens.ink,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search",
                                fontFamily = SpaceMonoFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 15.sp,
                                color = tokens.muted,
                                maxLines = 1
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                fontFamily = SpaceMonoFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 15.sp,
                                color = tokens.ink
                            ),
                            cursorBrush = SolidColor(tokens.ink),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Clear search" }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSearchChange("") }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(tokens.paper)
                                    .border(2.dp, tokens.ink, RoundedCornerShape(6.dp))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = tokens.ink,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            NeoHardShadowButton(
                visualWidth = 50.dp,
                visualHeight = 50.dp,
                cornerRadius = 10.dp,
                fillColor = tokens.tertiary,
                borderColor = tokens.ink,
                shadowColor = tokens.ink,
                shadowOffset = 4.dp,
                contentDescription = "Sort",
                onClick = onOpenSort
            ) {
                Icon(
                    imageVector = Icons.Default.SwapVert,
                    contentDescription = null,
                    tint = tokens.onColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // BLOCK C: VIEW SWITCHER (Only shown on main library, hidden inside folders)
        if (!isInsideFolder) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val items = listOf(
                    Triple(LibraryViewMode.FOLDER, tokens.primary, "Folders view"),
                    Triple(LibraryViewMode.RICH_LIST, tokens.secondary, "List view"),
                    Triple(LibraryViewMode.COMFORT_GRID, tokens.tertiary, "Grid view"),
                    Triple(LibraryViewMode.COMPACT_GRID, tokens.quaternary, "Compact view")
                )

                items.forEach { (mode, ownColor, desc) ->
                    val isSelected = selectedViewMode == mode
                    val icon = when (mode) {
                        LibraryViewMode.FOLDER -> Icons.Outlined.Folder
                        LibraryViewMode.RICH_LIST -> Icons.Outlined.FormatListBulleted
                        LibraryViewMode.COMFORT_GRID -> Icons.Outlined.GridView
                        LibraryViewMode.COMPACT_GRID -> Icons.Outlined.Apps
                    }

                    NeoToggleViewButton(
                        modifier = Modifier.weight(1f),
                        isSelected = isSelected,
                        ownFillColor = ownColor,
                        inkColor = tokens.ink,
                        onColor = tokens.onColor,
                        icon = icon,
                        contentDescription = desc,
                        onClick = { onSelectViewMode(mode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NeoHardShadowButton(
    visualWidth: Dp,
    visualHeight: Dp,
    cornerRadius: Dp,
    fillColor: Color,
    borderColor: Color,
    shadowColor: Color,
    shadowOffset: Dp,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val currentOffset by animateDpAsState(
        targetValue = if (isPressed) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "NeoBtnOffset"
    )
    val currentShadow by animateDpAsState(
        targetValue = if (isPressed) 0.dp else shadowOffset,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "NeoBtnShadow"
    )

    Box(
        contentAlignment = Alignment.TopStart,
        modifier = Modifier
            .size(
                width = maxOf(visualWidth + shadowOffset, 48.dp),
                height = maxOf(visualHeight + shadowOffset, 48.dp)
            )
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        if (currentShadow > 0.dp) {
            Box(
                modifier = Modifier
                    .size(visualWidth, visualHeight)
                    .offset(x = currentShadow, y = currentShadow)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(shadowColor)
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(visualWidth, visualHeight)
                .offset(x = currentOffset, y = currentOffset)
                .clip(RoundedCornerShape(cornerRadius))
                .background(fillColor)
                .border(2.dp, borderColor, RoundedCornerShape(cornerRadius))
        ) {
            content()
        }
    }
}

@Composable
private fun NeoToggleViewButton(
    modifier: Modifier = Modifier,
    isSelected: Boolean,
    ownFillColor: Color,
    inkColor: Color,
    onColor: Color,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val activeDown = isSelected || isPressed

    val offsetDp by animateDpAsState(
        targetValue = if (activeDown) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "ToggleOffset"
    )
    val shadowDp by animateDpAsState(
        targetValue = if (activeDown) 0.dp else 4.dp,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "ToggleShadow"
    )

    Box(
        modifier = modifier
            .height(54.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        if (shadowDp > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .offset(x = shadowDp, y = shadowDp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(inkColor)
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .offset(x = offsetDp, y = offsetDp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (isSelected) inkColor else ownFillColor)
                .border(2.dp, inkColor, RoundedCornerShape(10.dp))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) ownFillColor else onColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun HomeLibraryScreen(
    viewModel: HomeLibraryViewModel,
    onPlayVideo: (LocalVideoItem, List<LocalVideoItem>, Boolean) -> Unit
) {
    val context = LocalContext.current
    val tokens = LocalVdxTokens.current
    val prefs by viewModel.preferencesState.collectAsState()
    val videos by viewModel.enrichedVideos.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val selectedFolder by viewModel.selectedFolderPath.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val trashedVideos by viewModel.trashedVideos.collectAsState()
    val networkStreams by viewModel.networkStreams.collectAsState()
    val resumeTarget by viewModel.contextAwareResumeTarget.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.refreshLibrarySilently()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.refreshLibrarySilently()
        }
    }

    var showSortMenu by remember { mutableStateOf(false) }
    var showNetworkSheet by remember { mutableStateOf(false) }
    var showTrashSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var videoToRename by remember { mutableStateOf<LocalVideoItem?>(null) }
    var videoForProperties by remember { mutableStateOf<LocalVideoItem?>(null) }
    var showFabPreviewTooltip by remember { mutableStateOf(false) }

    val shareVideoAction: (LocalVideoItem) -> Unit = { video ->
        runCatching {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, video.contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share video"))
        }
    }

    BackHandler(enabled = selectedFolder != null || searchQuery.isNotEmpty()) {
        if (searchQuery.isNotEmpty()) {
            viewModel.setSearchQuery("")
        } else if (selectedFolder != null) {
            viewModel.openFolder(null)
        }
    }

    val displayedVideos = remember(videos, selectedFolder) {
        if (selectedFolder != null) {
            videos.filter { it.folderPath == selectedFolder }
        } else {
            videos
        }
    }

    Scaffold(
        containerColor = tokens.paper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Box {
                NeoBrutalistTopHeader(
                    videoCount = videos.size,
                    folderCount = folders.size,
                    searchQuery = searchQuery,
                    onSearchChange = { viewModel.setSearchQuery(it) },
                    selectedViewMode = prefs.libraryViewMode,
                    isInsideFolder = selectedFolder != null,
                    onSelectViewMode = { mode ->
                        viewModel.setViewMode(mode)
                        if (mode == LibraryViewMode.FOLDER) {
                            viewModel.openFolder(null)
                        }
                    },
                    onOpenCloud = { showNetworkSheet = true },
                    onOpenSettings = { showSettingsSheet = true },
                    onOpenSort = { showSortMenu = true }
                )

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    LibrarySortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = { Text(field.label) },
                            leadingIcon = {
                                if (prefs.sortField == field) {
                                    Icon(
                                        if (prefs.sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        tint = tokens.primary
                                    )
                                }
                            },
                            onClick = {
                                val nextAsc = if (prefs.sortField == field) !prefs.sortAscending else false
                                viewModel.setSortOption(field, nextAsc)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            resumeTarget?.let { targetVideo ->
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedVisibility(visible = showFabPreviewTooltip) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = tokens.ink,
                            shadowElevation = 6.dp
                        ) {
                            Text(
                                text = "Resume: ${targetVideo.titleWithoutExt} (${FormatUtils.formatDurationMs(targetVideo.savedPositionMs)})",
                                style = TabularNumeralStyle,
                                fontSize = 12.sp,
                                color = tokens.paper,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { onPlayVideo(targetVideo, displayedVideos, true) },
                                onLongClick = { showFabPreviewTooltip = !showFabPreviewTooltip }
                            )
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = tokens.primary,
                            border = BorderStroke(2.dp, tokens.ink),
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = tokens.onColor,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        CircularProgressIndicator(
                            progress = { targetVideo.completionFraction.coerceIn(0.05f, 1f) },
                            modifier = Modifier.size(58.dp),
                            color = tokens.ink,
                            trackColor = Color.Transparent,
                            strokeWidth = 3.dp,
                            strokeCap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedFolder != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { viewModel.openFolder(null) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = File(selectedFolder!!).name.ifBlank { "Folder" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = tokens.ink,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (!hasPermission) {
                    PermissionGrantCard(
                        onRequestPermission = { permissionLauncher.launch(requiredPermission) },
                        onOpenNetworkStream = { showNetworkSheet = true }
                    )
                } else {
                    if (prefs.libraryViewMode == LibraryViewMode.FOLDER && selectedFolder == null && searchQuery.isBlank()) {
                        if (folders.isEmpty()) {
                            EmptyLibraryView(
                                onRefresh = { viewModel.refreshLibrarySilently() },
                                onOpenNetworkStream = { showNetworkSheet = true }
                            )
                        } else {
                            FolderListView(
                                folders = folders,
                                onFolderClick = { folder -> viewModel.openFolder(folder.folderPath) }
                            )
                        }
                    } else {
                        if (displayedVideos.isEmpty()) {
                            EmptyLibraryView(
                                onRefresh = { viewModel.refreshLibrarySilently() },
                                onOpenNetworkStream = { showNetworkSheet = true }
                            )
                        } else {
                            val effectiveLayout = if (selectedFolder != null) {
                                when (prefs.folderContentViewMode) {
                                    FolderContentViewMode.RICH_LIST -> LibraryViewMode.RICH_LIST
                                    FolderContentViewMode.COMFORT_GRID -> LibraryViewMode.COMFORT_GRID
                                    FolderContentViewMode.COMPACT_GRID -> LibraryViewMode.COMPACT_GRID
                                }
                            } else {
                                prefs.libraryViewMode
                            }

                            when (effectiveLayout) {
                                LibraryViewMode.FOLDER, LibraryViewMode.RICH_LIST -> {
                                    RichVideoListView(
                                        videos = displayedVideos,
                                        onVideoClick = { onPlayVideo(it, displayedVideos, true) },
                                        onShareClick = shareVideoAction,
                                        onPropertiesClick = { videoForProperties = it },
                                        onRenameClick = { videoToRename = it },
                                        onTrashClick = { viewModel.moveVideoToTrash(it) }
                                    )
                                }
                                LibraryViewMode.COMFORT_GRID -> {
                                    ComfortVideoGridView(
                                        videos = displayedVideos,
                                        onVideoClick = { onPlayVideo(it, displayedVideos, true) },
                                        onShareClick = shareVideoAction,
                                        onPropertiesClick = { videoForProperties = it },
                                        onRenameClick = { videoToRename = it },
                                        onTrashClick = { viewModel.moveVideoToTrash(it) }
                                    )
                                }
                                LibraryViewMode.COMPACT_GRID -> {
                                    CompactVideoGridView(
                                        videos = displayedVideos,
                                        onVideoClick = { onPlayVideo(it, displayedVideos, true) },
                                        onPropertiesClick = { videoForProperties = it }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    videoForProperties?.let { vid ->
        var probedMeta by remember(vid.filePath) { mutableStateOf<VideoTechnicalMetadata?>(null) }
        LaunchedEffect(vid) {
            probedMeta = VideoTechnicalProber.probeVideoFile(context, vid)
        }

        AlertDialog(
            onDismissRequest = { videoForProperties = null },
            containerColor = tokens.card,
            title = {
                Text("Properties", style = MaterialTheme.typography.titleLarge, color = tokens.ink)
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PropertyRow("File name", vid.displayName)
                    PropertyRow("Location", vid.folderPath)
                    PropertyRow("Size", "${FormatUtils.formatFileSize(vid.sizeBytes)} (${vid.sizeBytes} bytes)")
                    PropertyRow("Duration", FormatUtils.formatDurationMs(vid.durationMs))
                    PropertyRow("Resolution", "${vid.width} x ${vid.height} (${vid.resolutionBadge})")
                    PropertyRow("Video codec", probedMeta?.videoCodec ?: "Analyzing...")
                    PropertyRow("Audio codec", probedMeta?.audioCodec ?: "Analyzing...")
                    PropertyRow("Bitrate", probedMeta?.bitrateFormatted ?: "Analyzing...")
                    PropertyRow("Frame rate", probedMeta?.frameRateFormatted ?: "Analyzing...")
                    PropertyRow("HDR / Dynamic range", probedMeta?.hdrFormatDetails ?: "Analyzing...")
                    PropertyRow("Color space", probedMeta?.colorSpaceDetails ?: "Analyzing...")
                    PropertyRow("Modified", FormatUtils.formatDateModified(vid.dateModifiedSec))
                }
            },
            confirmButton = {
                Button(onClick = { videoForProperties = null }) {
                    Text("Close", color = tokens.onColor)
                }
            }
        )
    }

    videoToRename?.let { target ->
        var newTitleText by remember(target) { mutableStateOf(target.titleWithoutExt) }
        AlertDialog(
            onDismissRequest = { videoToRename = null },
            title = { Text("Rename video") },
            text = {
                OutlinedTextField(
                    value = newTitleText,
                    onValueChange = { newTitleText = it },
                    label = { Text("New title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.renameVideo(target, newTitleText)
                        videoToRename = null
                    }
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { videoToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showTrashSheet) {
        TrashBinModalSheet(
            trashedVideos = trashedVideos,
            onRestore = { viewModel.restoreTrashedVideo(it) },
            onDeleteForever = { viewModel.deleteTrashedVideoForever(it) },
            onEmptyTrash = { viewModel.emptyEntireTrash() },
            onDismiss = { showTrashSheet = false }
        )
    }

    if (showNetworkSheet) {
        NetworkStreamModalSheet(
            streamHistory = networkStreams,
            onLaunchStream = { url, title ->
                showNetworkSheet = false
                viewModel.saveAndLaunchNetworkStream(url, title) { streamItem ->
                    onPlayVideo(streamItem, listOf(streamItem), true)
                }
            },
            onRenameHistory = { item, newTitle ->
                viewModel.renameStreamHistoryItem(item, newTitle)
            },
            onDeleteHistory = { viewModel.deleteStreamHistoryItem(it) },
            onDismiss = { showNetworkSheet = false }
        )
    }

    if (showSettingsSheet) {
        AppSettingsModalSheet(
            prefs = prefs,
            trashedCount = trashedVideos.size,
            onOpenRecycleBin = {
                showSettingsSheet = false
                showTrashSheet = true
            },
            onThemeChange = { viewModel.setThemeMode(it) },
            onAppIconColorChange = { viewModel.setAppIconColor(it) },
            onFolderViewModeChange = { viewModel.setFolderContentViewMode(it) },
            onDecoderChange = { viewModel.setDefaultDecoder(it) },
            onHoldSpeedChange = { viewModel.setHoldFastForwardSpeed(it) },
            onSkipIntervalChange = { viewModel.setSkipIntervalSeconds(it) },
            onAutoPipChange = { viewModel.setAutoPip(it) },
            onBgAudioChange = { viewModel.setBackgroundAudio(it) },
            onDismiss = { showSettingsSheet = false }
        )
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    val tokens = LocalVdxTokens.current
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tokens.muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = tokens.ink)
    }
}

@Composable
private fun FolderListView(
    folders: List<VideoFolderItem>,
    onFolderClick: (VideoFolderItem) -> Unit
) {
    val tokens = LocalVdxTokens.current
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(folders, key = { it.folderPath }) { folder ->
            val hashMod = (folder.folderName.hashCode() and 0x7FFFFFFF) % 4
            val tileFill = when (hashMod) {
                0 -> tokens.primary
                1 -> tokens.secondary
                2 -> tokens.tertiary
                else -> tokens.quaternary
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tokens.card,
                border = BorderStroke(1.5.dp, tokens.ink.copy(alpha = 0.15f)),
                tonalElevation = 2.dp,
                onClick = { onFolderClick(folder) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tileFill)
                            .border(1.5.dp, tokens.ink, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = tokens.onColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folder.folderName,
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val countLabel = if (folder.videos.size == 1) "1 video" else "${folder.videos.size} videos"
                        Text(
                            text = "$countLabel • ${FormatUtils.formatFileSize(folder.totalSizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.muted,
                            maxLines = 1
                        )
                    }

                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = tokens.muted
                    )
                }
            }
        }
    }
}

@Composable
private fun RichVideoListView(
    videos: List<LocalVideoItem>,
    onVideoClick: (LocalVideoItem) -> Unit,
    onShareClick: (LocalVideoItem) -> Unit,
    onPropertiesClick: (LocalVideoItem) -> Unit,
    onRenameClick: (LocalVideoItem) -> Unit,
    onTrashClick: (LocalVideoItem) -> Unit
) {
    val tokens = LocalVdxTokens.current
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(videos, key = { it.filePath }) { video ->
            var menuExpanded by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tokens.card,
                border = BorderStroke(1.dp, tokens.ink.copy(alpha = 0.12f)),
                tonalElevation = 2.dp,
                onClick = { onVideoClick(video) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 112.dp, height = 64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tokens.ink.copy(alpha = 0.08f))
                    ) {
                        InstantVideoThumbnail(
                            video = video,
                            modifier = Modifier.fillMaxSize()
                        )

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF111111).copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                        ) {
                            Text(
                                text = FormatUtils.formatDurationMs(video.durationMs),
                                style = TabularMonoStyle,
                                fontSize = 11.sp,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        if (video.savedPositionMs > 0L) {
                            LinearProgressIndicator(
                                progress = { video.completionFraction },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = tokens.primary,
                                trackColor = Color(0xFF111111)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = video.titleWithoutExt,
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${video.resolutionBadge} • ${video.extensionBadge} • ${FormatUtils.formatFileSize(video.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.muted,
                            maxLines = 1
                        )
                    }

                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = tokens.ink)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onShareClick(video)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onRenameClick(video)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Properties") },
                                leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onPropertiesClick(video)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Move to Trash") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = null,
                                        tint = tokens.danger
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onTrashClick(video)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComfortVideoGridView(
    videos: List<LocalVideoItem>,
    onVideoClick: (LocalVideoItem) -> Unit,
    onShareClick: (LocalVideoItem) -> Unit,
    onPropertiesClick: (LocalVideoItem) -> Unit,
    onRenameClick: (LocalVideoItem) -> Unit,
    onTrashClick: (LocalVideoItem) -> Unit
) {
    val tokens = LocalVdxTokens.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(videos, key = { it.filePath }) { video ->
            var menuExpanded by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tokens.card,
                border = BorderStroke(1.dp, tokens.ink.copy(alpha = 0.12f)),
                tonalElevation = 2.dp,
                onClick = { onVideoClick(video) }
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(tokens.ink.copy(alpha = 0.08f))
                    ) {
                        InstantVideoThumbnail(
                            video = video,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF111111).copy(alpha = 0.85f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                        ) {
                            Text(
                                text = FormatUtils.formatDurationMs(video.durationMs),
                                style = TabularMonoStyle,
                                fontSize = 10.sp,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                        if (video.savedPositionMs > 0L) {
                            LinearProgressIndicator(
                                progress = { video.completionFraction },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = tokens.primary,
                                trackColor = Color(0xFF111111)
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = video.titleWithoutExt,
                                style = MaterialTheme.typography.titleSmall,
                                color = tokens.ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${video.resolutionBadge} • ${FormatUtils.formatFileSize(video.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.muted,
                                maxLines = 1
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = null, tint = tokens.ink, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    onClick = { menuExpanded = false; onShareClick(video) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = { menuExpanded = false; onRenameClick(video) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Properties") },
                                    onClick = { menuExpanded = false; onPropertiesClick(video) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Move to Trash") },
                                    onClick = { menuExpanded = false; onTrashClick(video) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactVideoGridView(
    videos: List<LocalVideoItem>,
    onVideoClick: (LocalVideoItem) -> Unit,
    onPropertiesClick: (LocalVideoItem) -> Unit
) {
    val tokens = LocalVdxTokens.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(videos, key = { it.filePath }) { video ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.card)
                    .combinedClickable(
                        onClick = { onVideoClick(video) },
                        onLongClick = { onPropertiesClick(video) }
                    )
            ) {
                InstantVideoThumbnail(
                    video = video,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                            )
                        )
                )
                Text(
                    text = video.titleWithoutExt,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                )
                if (video.savedPositionMs > 0L) {
                    LinearProgressIndicator(
                        progress = { video.completionFraction },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp),
                        color = tokens.primary,
                        trackColor = Color(0xFF111111)
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkStreamModalSheet(
    streamHistory: List<NetworkStreamHistoryEntity>,
    onLaunchStream: (String, String) -> Unit,
    onRenameHistory: (NetworkStreamHistoryEntity, String) -> Unit,
    onDeleteHistory: (NetworkStreamHistoryEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalVdxTokens.current
    var streamUrl by remember { mutableStateOf("") }
    var streamToRename by remember { mutableStateOf<NetworkStreamHistoryEntity?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.card
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Network stream", style = MaterialTheme.typography.titleLarge, color = tokens.ink)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    placeholder = { Text("https://, .m3u8, .mpd, .mkv") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (streamUrl.isNotBlank()) {
                            onLaunchStream(streamUrl, "")
                        }
                    },
                    enabled = streamUrl.isNotBlank(),
                    modifier = Modifier.height(54.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Play", color = tokens.onColor)
                }
            }

            if (streamHistory.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                Text("Recent streams", style = MaterialTheme.typography.titleSmall, color = tokens.ink)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    items(streamHistory, key = { it.streamUrl }) { item ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = tokens.paper,
                            onClick = { onLaunchStream(item.streamUrl, item.title) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = tokens.ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        item.streamUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { streamToRename = item },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DriveFileRenameOutline,
                                        contentDescription = "Rename stream",
                                        tint = tokens.ink,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteHistory(item) },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Delete",
                                        tint = tokens.ink,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    streamToRename?.let { target ->
        var newTitleText by remember(target) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { streamToRename = null },
            title = { Text("Rename stream") },
            text = {
                OutlinedTextField(
                    value = newTitleText,
                    onValueChange = { newTitleText = it },
                    label = { Text("Stream title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRenameHistory(target, newTitleText)
                        streamToRename = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { streamToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TrashBinModalSheet(
    trashedVideos: List<TrashedVideoEntity>,
    onRestore: (TrashedVideoEntity) -> Unit,
    onDeleteForever: (TrashedVideoEntity) -> Unit,
    onEmptyTrash: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalVdxTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.card
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Recycle bin", style = MaterialTheme.typography.titleLarge, color = tokens.ink)
                if (trashedVideos.isNotEmpty()) {
                    TextButton(onClick = onEmptyTrash) {
                        Text("Empty", color = tokens.danger)
                    }
                }
            }

            if (trashedVideos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Recycle bin is empty", color = tokens.muted)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    items(trashedVideos, key = { it.id }) { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = tokens.paper,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.displayName, style = MaterialTheme.typography.titleSmall, color = tokens.ink, maxLines = 1)
                                    Text(
                                        "${item.daysRemaining()} days left • ${FormatUtils.formatFileSize(item.sizeBytes)}",
                                        style = TabularNumeralStyle,
                                        fontSize = 12.sp,
                                        color = tokens.muted
                                    )
                                }
                                FilledTonalButton(onClick = { onRestore(item) }) {
                                    Text("Restore")
                                }
                                IconButton(onClick = { onDeleteForever(item) }) {
                                    Icon(
                                        Icons.Outlined.DeleteForever,
                                        contentDescription = "Delete forever",
                                        tint = tokens.danger
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AppSettingsModalSheet(
    prefs: AppPreferencesState,
    trashedCount: Int,
    onOpenRecycleBin: () -> Unit,
    onThemeChange: (AppThemeMode) -> Unit,
    onAppIconColorChange: (AppIconColor) -> Unit,
    onFolderViewModeChange: (FolderContentViewMode) -> Unit,
    onDecoderChange: (DecoderMode) -> Unit,
    onHoldSpeedChange: (Float) -> Unit,
    onSkipIntervalChange: (Int) -> Unit,
    onAutoPipChange: (Boolean) -> Unit,
    onBgAudioChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = LocalVdxTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.card
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineLarge, color = tokens.ink)

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tokens.paper,
                border = BorderStroke(1.5.dp, tokens.ink.copy(alpha = 0.2f)),
                onClick = onOpenRecycleBin,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tokens.secondary)
                            .border(1.5.dp, tokens.ink, RoundedCornerShape(10.dp))
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = tokens.onColor, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Recycle bin", style = MaterialTheme.typography.titleMedium, color = tokens.ink)
                        Text("$trashedCount items in trash", style = MaterialTheme.typography.bodySmall, color = tokens.muted)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = tokens.ink)
                }
            }

            Text("Color theme", style = MaterialTheme.typography.labelMedium, color = tokens.muted)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppThemeMode.entries.chunked(2).forEach { rowThemes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowThemes.forEach { mode ->
                            val t = resolveVdxTokens(mode)
                            val selected = prefs.themeMode == mode
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = t.paper,
                                border = BorderStroke(if (selected) 2.5.dp else 1.5.dp, t.ink),
                                onClick = { onThemeChange(mode) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            listOf(t.primary, t.secondary, t.tertiary, t.quaternary).forEach { swatch ->
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(swatch)
                                                        .border(1.5.dp, t.ink, RoundedCornerShape(4.dp))
                                                )
                                            }
                                        }
                                        if (selected) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(t.ink)
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = t.paper, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(mode.label, style = MaterialTheme.typography.labelMedium, color = t.ink)
                                }
                            }
                        }
                        if (rowThemes.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Text("App icon color", style = MaterialTheme.typography.labelMedium, color = tokens.muted)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppIconColor.entries.forEach { iconOption ->
                    val selected = prefs.appIconColor == iconOption
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(iconOption.previewHex))
                            .border(
                                width = if (selected) 3.dp else 1.5.dp,
                                color = if (selected) tokens.primary else tokens.ink,
                                shape = CircleShape
                            )
                            .clickable { onAppIconColorChange(iconOption) }
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = iconOption.label,
                                tint = if (iconOption == AppIconColor.ONYX) Color.White else Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Text("View mode inside folders", style = MaterialTheme.typography.labelMedium, color = tokens.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FolderContentViewMode.entries.forEach { fMode ->
                    FilterChip(
                        selected = prefs.folderContentViewMode == fMode,
                        onClick = { onFolderViewModeChange(fMode) },
                        label = { Text(fMode.label) }
                    )
                }
            }

            Text("Default decoder", style = MaterialTheme.typography.labelMedium, color = tokens.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DecoderMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.defaultDecoderMode == mode,
                        onClick = { onDecoderChange(mode) },
                        label = { Text(mode.badge) }
                    )
                }
            }

            Text(
                "Double-tap skip interval: ${prefs.skipIntervalSeconds}s",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.ink
            )
            Slider(
                value = prefs.skipIntervalSeconds.toFloat(),
                onValueChange = { onSkipIntervalChange(it.roundToInt()) },
                valueRange = 3f..30f
            )

            Text(
                "Hold fast-forward speed: ${String.format(Locale.US, "%.2fx", prefs.holdFastForwardSpeed)}",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.ink
            )
            Slider(
                value = prefs.holdFastForwardSpeed,
                onValueChange = { onHoldSpeedChange((it * 20f).roundToInt() / 20f) },
                valueRange = 1.25f..3.5f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Auto Picture-in-Picture", style = MaterialTheme.typography.bodyMedium, color = tokens.ink)
                Switch(checked = prefs.autoPipEnabled, onCheckedChange = onAutoPipChange)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Background audio playback", style = MaterialTheme.typography.bodyMedium, color = tokens.ink)
                Switch(checked = prefs.backgroundAudioEnabled, onCheckedChange = onBgAudioChange)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionGrantCard(
    onRequestPermission: () -> Unit,
    onOpenNetworkStream: () -> Unit
) {
    val tokens = LocalVdxTokens.current
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = tokens.card,
            border = BorderStroke(2.dp, tokens.ink),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Allow video access", style = MaterialTheme.typography.titleLarge, color = tokens.ink)
                Text(
                    "VDX Player needs storage permission to load your videos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.muted
                )
                Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant permission", color = tokens.onColor)
                }
                OutlinedButton(onClick = onOpenNetworkStream, modifier = Modifier.fillMaxWidth()) {
                    Text("Open network stream")
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryView(
    onRefresh: () -> Unit,
    onOpenNetworkStream: () -> Unit
) {
    val tokens = LocalVdxTokens.current
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("No videos found", style = MaterialTheme.typography.titleLarge, color = tokens.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onRefresh) {
                    Text("Rescan")
                }
                Button(onClick = onOpenNetworkStream) {
                    Text("Stream URL", color = tokens.onColor)
                }
            }
        }
    }
}
