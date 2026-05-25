package ink.sunrui.feiniutv

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ink.sunrui.feiniutv.databinding.ActivityPlayerBinding
import ink.sunrui.feiniutv.network.NasApiClient
import kotlin.concurrent.thread
import tv.danmaku.ijk.media.player.IjkMediaPlayer

class PlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TOKEN = "extra_token"
        const val EXTRA_ITEM_GUID = "extra_item_guid"

        /**
         * 联播队列：当 itemGuid 属于一个剧集列表时，传入完整的 guid / title 列表 + 当前索引，
         * 播放正常结束时会自动切到下一集（[playNextEpisodeIfAny]）。
         * 电影 / 单集 / 直接播放 TV 入口时不传，行为退化为播完即 finish。
         */
        const val EXTRA_EPISODE_GUIDS = "extra_episode_guids"
        const val EXTRA_EPISODE_TITLES = "extra_episode_titles"
        const val EXTRA_EPISODE_INDEX = "extra_episode_index"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var mediaPlayer: IjkMediaPlayer? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var pendingPlayUrl: String? = null
    private var pendingToken: String = ""
    private var fallbackStage = 0

    private var token: String = ""
    private var itemGuid: String = ""
    private var mediaGuid: String = ""

    // 联播队列。若非空，则正常播完后切到下一集。
    private var episodeGuids: ArrayList<String>? = null
    private var episodeTitles: ArrayList<String>? = null
    private var episodeIndex: Int = -1

    // 当前播放会话状态（设置弹窗用）
    private var currentVideoGuid: String? = null
    private var currentAudioGuid: String? = null
    private var currentSubtitleGuid: String? = null  // 空串表示"关闭字幕"
    private var currentResolution: String = ""
    private var currentBitrate: Long = 0L
    private var currentSpeed: Float = 1.0f
    // 流轨道列表缓存（避免反复请求）
    private var cachedStreams: ink.sunrui.feiniutv.network.MediaStreams? = null

    private var pendingSeekPosition: Long = -1L
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val seekRunnable = Runnable { executeSeek() }
    private var originalPositionBeforeSeek: Long = 0L

    // 进度更新：每 500ms 刷新一次底部进度条与时间码
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressOverlay()
            progressHandler.postDelayed(this, 500)
        }
    }

    // overlay 自动淡出：3 秒无按键则隐藏；任何按键唤醒
    private val overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { setOverlayVisible(false) }
    private var overlayVisible = true

    private var playerInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        itemGuid = intent.getStringExtra(EXTRA_ITEM_GUID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        // 读取联播队列（可空，仅剧集播放时由 DetailActivity 传入）
        episodeGuids = intent.getStringArrayListExtra(EXTRA_EPISODE_GUIDS)
        episodeTitles = intent.getStringArrayListExtra(EXTRA_EPISODE_TITLES)
        episodeIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, -1)

        if (token.isBlank() || itemGuid.isBlank()) {
            Toast.makeText(this, "缺少播放参数", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        IjkMediaPlayer.loadLibrariesOnce(null)
        IjkMediaPlayer.native_profileBegin("libijkplayer.so")
        playerInitialized = true

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleText.text = if (title.isNotBlank()) "正在播放：$title" else "正在播放"
        binding.playerSurface.holder.addCallback(this)
        binding.loadingView.visibility = android.view.View.VISIBLE
        // 进入页面时 overlay 可见 3 秒，之后淡出
        scheduleOverlayHide()

        resolveAndPlay()
    }

    private fun resolveAndPlay() {
        binding.loadingView.visibility = android.view.View.VISIBLE
        thread {
            val url = NasApiClient.resolvePlayUrl(token, itemGuid, preferLowestQuality = true)
            runOnUiThread {
                if (!url.isNullOrBlank()) {
                    startPlayback(url, token)
                } else {
                    binding.loadingView.visibility = android.view.View.GONE
                    Toast.makeText(this, "获取播放地址失败", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun startPlayback(sourceUrl: String, authToken: String) {
        pendingPlayUrl = sourceUrl
        pendingToken = authToken
        binding.loadingView.visibility = android.view.View.VISIBLE
        tryStartPlayerIfReady()
    }

    private fun tryStartPlayerIfReady() {
        val holder = surfaceHolder ?: return
        val sourceUrl = pendingPlayUrl ?: return

        releasePlayer()

        val player = IjkMediaPlayer().apply {
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15_000_000)
            if (pendingToken.isNotBlank()) {
                // 飞牛 nginx 对 HEAD/某些路由依赖 cookie:mode=relay
                // 缺它时 HEAD /preset.m3u8 / HEAD /*.ts 会被路由到 SPA index.html（3117 字节 HTML），
                // ffmpeg 误判分段大小导致播放 ~3-4 分钟后假死或提前 Completion。
                // 多 header 在 IjkPlayer "headers" 选项里用 \r\n 分隔。
                val headerValue = "Authorization: $pendingToken\r\n" +
                    "Cookie: mode=relay\r\n"
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", headerValue)
            }

            setOnPreparedListener {
                Log.i(TAG, "Ijk prepared, start playback source=$sourceUrl")
                binding.loadingView.visibility = android.view.View.GONE
                start()
                // 续播 seek（来自 resumeAfterPrematureEnd）
                if (pendingSeekAfterPrepareMs >= 0) {
                    val pos = pendingSeekAfterPrepareMs
                    pendingSeekAfterPrepareMs = -1L
                    Log.i(TAG, "Auto-seeking to ${pos}ms after prepare (resume)")
                    seekTo(pos)
                }
                // 起播后开启进度更新
                progressHandler.removeCallbacks(progressRunnable)
                progressHandler.post(progressRunnable)
            }

            setOnInfoListener { _, what, extra ->
                Log.d(TAG, "Ijk info what=$what extra=$extra")
                false
            }

            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Ijk error what=$what extra=$extra fallbackStage=$fallbackStage")
                binding.loadingView.visibility = android.view.View.GONE
                handlePlaybackError(what, extra)
                true
            }

            setOnSeekCompleteListener {
                binding.loadingView.visibility = android.view.View.GONE
            }

            setOnCompletionListener { mp ->
                val pos = mp.currentPosition
                val dur = mp.duration
                Log.i(TAG, "Ijk completion pos=${pos}ms dur=${dur}ms")
                // 飞牛的 HLS preset.m3u8 是逐段转码、会话型——
                // 转码会话或某段超时后 .ts 会 410 Gone，ffmpeg 视为流末触发 completion。
                // 经验阈值：若 pos < dur 的 95%，认为是"伪 completion"，刷新 play_link 续播。
                val isPrematureEnd = dur > 0 && pos < (dur * 0.95).toLong()
                if (isPrematureEnd) {
                    Log.w(TAG, "Premature completion detected (${pos}ms / ${dur}ms = ${pos * 100 / dur}%) — refreshing play_link and resuming")
                    resumeAfterPrematureEnd(pos)
                } else {
                    Log.i(TAG, "Playback finished normally")
                    if (!playNextEpisodeIfAny()) {
                        // 无下一集 → 退出播放页
                        finish()
                    }
                }
            }
        }

        mediaPlayer = player

        runCatching {
            player.setDisplay(holder)
            player.setDataSource(sourceUrl)
            Log.i(TAG, "Ijk setDataSource source=$sourceUrl")
            player.prepareAsync()
        }.onFailure {
            Log.e(TAG, "Ijk prepare failed source=$sourceUrl", it)
            binding.loadingView.visibility = android.view.View.GONE
            handlePlaybackError(1, -1)
        }
    }

    private fun handlePlaybackError(what: Int, extra: Int) {
        if (token.isBlank()) {
            Toast.makeText(this, getString(R.string.play_failed, what, extra), Toast.LENGTH_SHORT).show()
            return
        }

        when (fallbackStage) {
            0 -> {
                fallbackStage = 1
                if (itemGuid.isBlank()) {
                    Toast.makeText(this, getString(R.string.play_failed, what, extra), Toast.LENGTH_SHORT).show()
                    return
                }
                thread {
                    val fallback = NasApiClient.refetchPlayableUrlByItemGuid(token, itemGuid, preferLowestQuality = true)
                    runOnUiThread {
                        if (!fallback.isNullOrBlank()) {
                            Log.i(TAG, "fallback stage1 using low quality url=$fallback")
                            startPlayback(fallback, token)
                        } else {
                            handlePlaybackError(what, extra)
                        }
                    }
                }
            }

            1 -> {
                fallbackStage = 2
                if (mediaGuid.isBlank()) {
                    Toast.makeText(this, getString(R.string.play_failed, what, extra), Toast.LENGTH_SHORT).show()
                    return
                }
                val rangeUrl = NasApiClient.buildOriginalRangeUrl(mediaGuid)
                Log.i(TAG, "fallback stage2 using media range url=$rangeUrl")
                startPlayback(rangeUrl, token)
            }

            else -> {
                Toast.makeText(this, getString(R.string.play_failed, what, extra), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 转码会话过期/分段 410 导致的"伪 completion"恢复：
     *   1. 重新调 NasApiClient 拿一个新的 play_link（同 item，preferLowestQuality 保险些）
     *   2. 在新 player 起播后 seek 回 [resumePositionMs]，无缝续播
     *
     * 注意：若用户在 95% 以后看完叫"正常 completion"——这里不会被触发；
     *      若网络刚好闪断也会走这路，连续两次失败时则放弃（避免死循环）。
     */
    @Volatile private var resumeRetryCount = 0
    private fun resumeAfterPrematureEnd(resumePositionMs: Long) {
        if (resumeRetryCount >= 3) {
            Log.w(TAG, "resumeAfterPrematureEnd: hit retry cap, giving up at $resumePositionMs ms")
            Toast.makeText(this, "续播失败，请重新打开", Toast.LENGTH_SHORT).show()
            return
        }
        resumeRetryCount++
        binding.loadingView.visibility = android.view.View.VISIBLE
        Toast.makeText(this, "续播中…", Toast.LENGTH_SHORT).show()
        thread {
            val newUrl = NasApiClient.resolvePlayUrl(token, itemGuid, preferLowestQuality = true)
            runOnUiThread {
                if (newUrl.isNullOrBlank()) {
                    Log.e(TAG, "resumeAfterPrematureEnd: refetch failed")
                    binding.loadingView.visibility = android.view.View.GONE
                    Toast.makeText(this, "续播失败：刷新地址失败", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Log.i(TAG, "resumeAfterPrematureEnd: new url=$newUrl, will seek to ${resumePositionMs}ms after prepare")
                pendingSeekAfterPrepareMs = resumePositionMs
                fallbackStage = 0
                startPlayback(newUrl, token)
            }
        }
    }

    /** 下一次 onPrepared 触发后立即 seek 到的位置；负数表示无 pending seek */
    @Volatile private var pendingSeekAfterPrepareMs: Long = -1L

    private fun releasePlayer() {
        val p = mediaPlayer ?: return
        mediaPlayer = null
        runCatching {
            p.setDisplay(null)
            p.stop()
        }
        runCatching { p.release() }
    }

    /**
     * 自动联播下一集。
     *
     * 返回 true 表示已切到下一集并触发 [resolveAndPlay]；返回 false 表示没有下一集（队列为空 / 已是最后一集），
     * 调用方应自行决定后续（通常是 finish）。
     *
     * 重置全部播放会话状态：播放器、流缓存、fallback 阶段、seek、进度更新、overlay。
     */
    private fun playNextEpisodeIfAny(): Boolean {
        val guids = episodeGuids ?: return false
        val nextIdx = episodeIndex + 1
        if (nextIdx < 0 || nextIdx >= guids.size) return false

        val nextGuid = guids[nextIdx]
        val nextTitle = episodeTitles?.getOrNull(nextIdx).orEmpty()

        Log.i(TAG, "Auto-next episode: index=$nextIdx guid=$nextGuid title=$nextTitle")
        Toast.makeText(this, "下一集：${nextTitle.ifBlank { "第 ${nextIdx + 1} 集" }}", Toast.LENGTH_SHORT).show()

        // 推进队列指针
        episodeIndex = nextIdx
        itemGuid = nextGuid

        // 重置全部播放会话状态（参考 onDestroy / onResume 的释放路径）
        progressHandler.removeCallbacks(progressRunnable)
        seekHandler.removeCallbacks(seekRunnable)
        releasePlayer()
        pendingPlayUrl = null
        pendingSeekAfterPrepareMs = -1L
        pendingSeekPosition = -1L
        fallbackStage = 0
        cachedStreams = null
        currentVideoGuid = null
        currentAudioGuid = null
        currentSubtitleGuid = null
        currentResolution = ""
        currentBitrate = 0L
        // currentSpeed 保留（用户期望连贯）

        binding.titleText.text = if (nextTitle.isNotBlank()) "正在播放：$nextTitle" else "正在播放"
        setOverlayVisible(true)
        scheduleOverlayHide()

        resolveAndPlay()
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        tryStartPlayerIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceHolder = holder
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (surfaceHolder == holder) {
            surfaceHolder = null
        }
        releasePlayer()
    }

    override fun onPause() {
        super.onPause()
        runCatching { mediaPlayer?.pause() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 任何按键都唤醒 overlay
        setOverlayVisible(true)
        scheduleOverlayHide()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                handleSeek(event, true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                handleSeek(event, false)
                return true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_UP -> {
                showPlayerSettingsDialog()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                        Toast.makeText(this, "暂停", Toast.LENGTH_SHORT).show()
                    } else {
                        it.start()
                        Toast.makeText(this, "继续播放", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                mediaPlayer?.let {
                    if (!it.isPlaying) {
                        it.start()
                        Toast.makeText(this, "继续播放", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                        Toast.makeText(this, "暂停", Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun executeSeek() {
        val p = mediaPlayer ?: return
        if (pendingSeekPosition >= 0) {
            binding.loadingView.visibility = android.view.View.VISIBLE
            p.seekTo(pendingSeekPosition)
            pendingSeekPosition = -1L
        }
        binding.seekOverlayText.visibility = android.view.View.GONE
    }

    private fun handleSeek(event: KeyEvent?, forward: Boolean) {
        val p = mediaPlayer ?: return
        if (pendingSeekPosition < 0) {
            originalPositionBeforeSeek = p.currentPosition
            pendingSeekPosition = originalPositionBeforeSeek
        }

        val repeat = event?.repeatCount ?: 0
        val multiplier = Math.min(16L, 1L + (repeat / 2).toLong())
        val stepMs = 10000L * multiplier

        val duration = p.duration
        if (forward) {
            pendingSeekPosition = Math.min(pendingSeekPosition + stepMs, duration)
        } else {
            pendingSeekPosition = Math.max(pendingSeekPosition - stepMs, 0L)
        }

        val offsetSec = (pendingSeekPosition - originalPositionBeforeSeek) / 1000
        val sign = if (offsetSec >= 0) "+" else ""
        val speedText = if (multiplier > 1) " (x${multiplier})" else ""

        binding.seekOverlayText.text = "${sign}${offsetSec}s${speedText}"
        binding.seekOverlayText.visibility = android.view.View.VISIBLE

        seekHandler.removeCallbacks(seekRunnable)
        seekHandler.postDelayed(seekRunnable, 500)
    }

    // ============================== 播放设置弹窗 ==============================
    // 顶部菜单 4 项：分辨率 / 音轨 / 字幕 / 倍速
    // 首次打开会拉一次 play/info + stream/list 以确定当前状态和可选项

    private fun showPlayerSettingsDialog() {
        if (token.isBlank() || itemGuid.isBlank()) {
            Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show()
            return
        }
        binding.loadingView.visibility = android.view.View.VISIBLE
        thread {
            // 1) 确保播放状态已知（首次进入 mediaGuid 可能为空）
            if (mediaGuid.isBlank()) {
                val infoResult = NasApiClient.fetchPlayInfoState(token, itemGuid)
                val state = infoResult.getOrNull()
                if (state == null) {
                    runOnUiThread {
                        binding.loadingView.visibility = android.view.View.GONE
                        Toast.makeText(this, "获取播放信息失败", Toast.LENGTH_SHORT).show()
                    }
                    return@thread
                }
                mediaGuid = state.mediaGuid
                if (currentVideoGuid == null) currentVideoGuid = state.videoGuid
                if (currentAudioGuid == null) currentAudioGuid = state.audioGuid
                if (currentSubtitleGuid == null) currentSubtitleGuid = state.subtitleGuid
            }
            // 2) 获取流轨道列表（缓存）— stream/list 接口收的是 item_guid
            if (cachedStreams == null) {
                val streamResult = NasApiClient.fetchMediaStreams(token, itemGuid)
                cachedStreams = streamResult.getOrNull()
                // 失败不致命，弹窗仍能切分辨率/倍速，只是音轨字幕不可选
            }
            runOnUiThread {
                binding.loadingView.visibility = android.view.View.GONE
                renderSettingsMenu()
            }
        }
    }

    private fun renderSettingsMenu() {
        val streams = cachedStreams
        val resLabel = if (currentResolution.isNotBlank()) {
            "${currentResolution} (${currentBitrate / 1000}kbps)"
        } else "自动"
        val audioLabel = streams?.audioTracks?.find { it.guid == currentAudioGuid }?.displayLabel() ?: "默认"
        val subLabel = when {
            currentSubtitleGuid.isNullOrEmpty() -> "已关闭"
            else -> streams?.subtitleTracks?.find { it.guid == currentSubtitleGuid }?.displayLabel() ?: "默认"
        }
        val speedLabel = if (currentSpeed == 1.0f) "正常" else "${currentSpeed}×"

        val items = arrayOf(
            "分辨率：$resLabel",
            "音轨：$audioLabel",
            "字幕：$subLabel",
            "倍速：$speedLabel"
        )
        AlertDialog.Builder(this)
            .setTitle("播放设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showResolutionDialog()
                    1 -> showAudioDialog()
                    2 -> showSubtitleDialog()
                    3 -> showSpeedDialog()
                }
            }
            .show()
    }

    // -------- 分辨率 --------
    private fun showResolutionDialog() {
        if (mediaGuid.isBlank()) {
            Toast.makeText(this, "媒体信息缺失", Toast.LENGTH_SHORT).show()
            return
        }
        binding.loadingView.visibility = android.view.View.VISIBLE
        thread {
            val result = NasApiClient.fetchQualities(token, mediaGuid)
            runOnUiThread {
                binding.loadingView.visibility = android.view.View.GONE
                if (result.isFailure) {
                    Toast.makeText(this, "获取分辨率失败", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val qs = result.getOrNull().orEmpty()
                if (qs.isEmpty()) {
                    Toast.makeText(this, "没有可选分辨率", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // 同分辨率取最高码率
                val best = linkedMapOf<String, NasApiClient.Quality>()
                qs.forEach { q -> if ((best[q.resolution]?.bitrate ?: -1L) < q.bitrate) best[q.resolution] = q }
                val display = best.values.sortedByDescending { it.bitrate }
                val labels = display.map {
                    val tag = if (it.resolution == currentResolution) " ✓" else ""
                    "${it.resolution} (${it.bitrate / 1000}kbps)$tag"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("选择分辨率")
                    .setItems(labels) { _, idx ->
                        val sel = display[idx]
                        currentResolution = sel.resolution
                        currentBitrate = sel.bitrate
                        Toast.makeText(this, "切换到 ${sel.resolution}", Toast.LENGTH_SHORT).show()
                        reloadCurrentPlayback()
                    }
                    .show()
            }
        }
    }

    // -------- 音轨 --------
    private fun showAudioDialog() {
        val streams = cachedStreams
        if (streams == null || streams.audioTracks.isEmpty()) {
            Toast.makeText(this, "无可切换音轨", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = streams.audioTracks.map {
            val tag = if (it.guid == currentAudioGuid) " ✓" else ""
            "${it.displayLabel()}$tag"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择音轨")
            .setItems(labels) { _, idx ->
                val sel = streams.audioTracks[idx]
                if (sel.guid != currentAudioGuid) {
                    currentAudioGuid = sel.guid
                    Toast.makeText(this, "切换音轨：${sel.displayLabel()}", Toast.LENGTH_SHORT).show()
                    reloadCurrentPlayback()
                }
            }
            .show()
    }

    // -------- 字幕 --------
    private fun showSubtitleDialog() {
        val streams = cachedStreams
        val subs = streams?.subtitleTracks.orEmpty()
        // 首项始终是"关闭字幕"
        val options = mutableListOf<Pair<String, String>>()  // label → guid（空串表关闭）
        options += "关闭字幕${if (currentSubtitleGuid.isNullOrEmpty()) " ✓" else ""}" to ""
        subs.forEach { s ->
            val tag = if (s.guid == currentSubtitleGuid) " ✓" else ""
            options += "${s.displayLabel()}$tag" to s.guid
        }
        if (options.size == 1) {
            Toast.makeText(this, "无可用字幕", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = options.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择字幕")
            .setItems(labels) { _, idx ->
                val newGuid = options[idx].second
                if (newGuid != (currentSubtitleGuid ?: "")) {
                    currentSubtitleGuid = newGuid
                    Toast.makeText(this, "切换字幕：${options[idx].first.removeSuffix(" ✓")}", Toast.LENGTH_SHORT).show()
                    reloadCurrentPlayback()
                }
            }
            .show()
    }

    // -------- 倍速（本地 IjkMediaPlayer.setSpeed，不重新拉流） --------
    private fun showSpeedDialog() {
        val rates = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val labels = rates.map {
            val name = if (it == 1.0f) "正常 (1.0×)" else "${it}×"
            if (it == currentSpeed) "$name ✓" else name
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("播放倍速")
            .setItems(labels) { _, idx ->
                val rate = rates[idx]
                if (rate != currentSpeed) {
                    currentSpeed = rate
                    runCatching { mediaPlayer?.setSpeed(rate) }
                        .onFailure { Log.e(TAG, "setSpeed failed", it) }
                    Toast.makeText(this, "倍速：${if (rate == 1.0f) "正常" else "${rate}×"}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // 用 currentXxx 状态重新拉 play_link 并重启播放器
    private fun reloadCurrentPlayback() {
        if (mediaGuid.isBlank() || currentResolution.isBlank() || currentBitrate <= 0L) {
            // 分辨率/码率还没确定的话先不做切换，让用户先选个分辨率
            // 但用户切了音轨/字幕也可能触发：这种情况下我们 fallback 用低码率
            // 简单兜底：用 refetchPlayableUrlByItemGuid 拉一个干净的 URL
            binding.loadingView.visibility = android.view.View.VISIBLE
            thread {
                val url = NasApiClient.refetchPlayableUrlByItemGuid(token, itemGuid, preferLowestQuality = true)
                runOnUiThread {
                    if (url.isNullOrBlank()) {
                        binding.loadingView.visibility = android.view.View.GONE
                        Toast.makeText(this, "切换失败", Toast.LENGTH_SHORT).show()
                    } else {
                        fallbackStage = 0
                        startPlayback(url, token)
                        // 重启后需要恢复倍速（IjkMediaPlayer 重建后 speed 复位）
                        applySpeedAfterStart()
                    }
                }
            }
            return
        }
        binding.loadingView.visibility = android.view.View.VISIBLE
        thread {
            val url = NasApiClient.getPlayUrl(
                token = token,
                mediaGuid = mediaGuid,
                resolution = currentResolution,
                bitrate = currentBitrate,
                videoGuid = currentVideoGuid,
                audioGuid = currentAudioGuid,
                // 空串表示关闭字幕：传 null 让后端使用默认（即不附带），用空串则后端可能保留——这里走 null 兜底
                subtitleGuid = currentSubtitleGuid?.ifBlank { null }
            )
            runOnUiThread {
                if (url.isNullOrBlank()) {
                    binding.loadingView.visibility = android.view.View.GONE
                    Toast.makeText(this, "切换失败", Toast.LENGTH_SHORT).show()
                } else {
                    fallbackStage = 0
                    startPlayback(url, token)
                    applySpeedAfterStart()
                }
            }
        }
    }

    // 重建播放器后，等 prepared 再把 setSpeed 应用上去
    private fun applySpeedAfterStart() {
        if (currentSpeed == 1.0f) return
        // setOnPreparedListener 已在 tryStartPlayerIfReady 设置；这里追加一次延迟设置兜底
        binding.root.postDelayed({
            runCatching { mediaPlayer?.setSpeed(currentSpeed) }
                .onFailure { Log.w(TAG, "delayed setSpeed failed", it) }
        }, 1500)
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        seekHandler.removeCallbacks(seekRunnable)
        releasePlayer()
        if (playerInitialized) {
            runCatching { IjkMediaPlayer.native_profileEnd() }
        }
    }

    // ============================== overlay 控制 ==============================

    private fun setOverlayVisible(visible: Boolean) {
        if (overlayVisible == visible) return
        overlayVisible = visible
        val targetAlpha = if (visible) 1f else 0f
        binding.topOverlay.animate().alpha(targetAlpha).setDuration(180).start()
        binding.bottomOverlay.animate().alpha(targetAlpha).setDuration(180).start()
    }

    private fun scheduleOverlayHide() {
        overlayHandler.removeCallbacks(hideOverlayRunnable)
        overlayHandler.postDelayed(hideOverlayRunnable, 3000)
    }

    private fun updateProgressOverlay() {
        val p = mediaPlayer ?: return
        val current = p.currentPosition.coerceAtLeast(0)
        val total = p.duration.coerceAtLeast(0)
        binding.currentTimeText.text = formatTime(current)
        binding.totalTimeText.text = if (total > 0) formatTime(total) else "--:--"
        if (total > 0) {
            val progress = ((current.toDouble() / total.toDouble()) * 1000).toInt().coerceIn(0, 1000)
            binding.playerProgress.progress = progress
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }
}
