package com.flagship.videoplayer

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.flagship.videoplayer.data.*
import com.flagship.videoplayer.engine.PlayerViewModel
import com.flagship.videoplayer.ui.home.HomeLibraryScreen
import com.flagship.videoplayer.ui.home.HomeLibraryViewModel
import com.flagship.videoplayer.ui.player.FlagshipPlayerScreen
import com.flagship.videoplayer.ui.theme.FlagshipVideoPlayerTheme

class MainActivity : ComponentActivity() {

    private lateinit var homeViewModel: HomeLibraryViewModel
    private lateinit var playerViewModel: PlayerViewModel
    private var isInPipModeState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = FlagshipDatabase.getInstance(applicationContext)
        val prefsRepo = AppPreferencesRepository(applicationContext)

        homeViewModel = ViewModelProvider(
            this,
            HomeLibraryViewModel.Factory(applicationContext, database.dao(), prefsRepo)
        )[HomeLibraryViewModel::class.java]

        playerViewModel = ViewModelProvider(
            this,
            PlayerViewModel.Factory(applicationContext, database.dao(), prefsRepo)
        )[PlayerViewModel::class.java]

        handleIncomingViewIntent(intent)

        setContent {
            val prefs by homeViewModel.preferencesState.collectAsState()
            val activeVideo by playerViewModel.activeVideo.collectAsState()

            FlagshipVideoPlayerTheme(
                themeMode = prefs.themeMode
            ) {
                if (activeVideo != null) {
                    FlagshipPlayerScreen(
                        viewModel = playerViewModel,
                        isInPipMode = isInPipModeState,
                        onEnterPipMode = { triggerPictureInPicture() },
                        onExitPlayer = {
                            playerViewModel.stopAndReleasePlayer()
                            homeViewModel.refreshLibrarySilently()
                        }
                    )
                } else {
                    HomeLibraryScreen(
                        viewModel = homeViewModel,
                        onPlayVideo = { videoItem, playlist, autoResume ->
                            playerViewModel.loadAndPlayVideo(videoItem, playlist, autoResume)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingViewIntent(intent)
    }

    private fun handleIncomingViewIntent(intent: Intent?) {
        val dataUri: Uri = intent?.data ?: return
        val scheme = dataUri.scheme?.lowercase() ?: ""
        val isNetwork = scheme == "http" || scheme == "https" || scheme == "rtsp"
        val displayName = dataUri.lastPathSegment?.substringAfterLast('/') ?: "External media"
        val fingerprint = if (isNetwork) {
            FileFingerprintEngine.computeStreamFingerprint(dataUri.toString())
        } else {
            FileFingerprintEngine.computeFingerprint(dataUri.path ?: dataUri.toString(), 0L)
        }

        val externalItem = LocalVideoItem(
            mediaStoreId = -1L,
            contentUri = dataUri,
            filePath = dataUri.toString(),
            folderPath = "external://intent",
            folderName = "External videos",
            displayName = displayName,
            titleWithoutExt = displayName.substringBeforeLast('.'),
            extensionBadge = if (isNetwork) NetworkStreamHistoryEntity.detectProtocolBadge(dataUri.toString()) else "VIDEO",
            sizeBytes = 0L,
            durationMs = 0L,
            width = 1920,
            height = 1080,
            resolutionBadge = "HD",
            dateModifiedSec = System.currentTimeMillis() / 1000L,
            sidecarSubtitlePaths = emptyList(),
            fileFingerprint = fingerprint,
            isNetworkStream = isNetwork
        )
        playerViewModel.loadAndPlayVideo(externalItem, listOf(externalItem), autoResume = true)
    }

    private fun triggerPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val vid = playerViewModel.activeVideo.value
                val w = (vid?.width ?: 16).coerceIn(1, 3840)
                val h = (vid?.height ?: 9).coerceIn(1, 2160)
                val rational = Rational(w, h).let {
                    if (it.toFloat() in 0.42f..2.38f) it else Rational(16, 9)
                }
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setSeamlessResizeEnabled(false)
                }
                enterPictureInPictureMode(builder.build())
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val isPlaying = playerViewModel.isPlaying.value
        val autoPip = playerViewModel.preferencesState.value.autoPipEnabled
        if (playerViewModel.activeVideo.value != null && isPlaying && autoPip) {
            triggerPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            playerViewModel.stopAndReleasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        val keepBgAudio = playerViewModel.preferencesState.value.backgroundAudioEnabled
        if (!isInPipModeState && !keepBgAudio) {
            playerViewModel.exoPlayerFlow.value?.pause()
        }
    }
}
