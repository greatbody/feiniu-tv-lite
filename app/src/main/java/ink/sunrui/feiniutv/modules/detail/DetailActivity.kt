package ink.sunrui.feiniutv.modules.detail

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import ink.sunrui.feiniutv.Episode
import ink.sunrui.feiniutv.ItemDetail
import ink.sunrui.feiniutv.PlayerActivity
import ink.sunrui.feiniutv.R
import ink.sunrui.feiniutv.databinding.ActivityDetailBinding
import ink.sunrui.feiniutv.store.AccountStore

/**
 * 详情页（类型分叉）：
 *   - Movie / Video：底部 立即播放 + 返回
 *   - TV / Season：底部 季 Tab（多季时）+ 横排剧集卡，点击单集即播
 *   - Episode：当 Movie 处理
 *
 * 焦点策略：
 *   - Movie：默认在 playButton
 *   - TV：默认在剧集列表首项（最近能播的）；返回按钮挪到右上避免抢焦点
 */
class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEM_GUID = "extra_item_guid"
        const val EXTRA_ITEM_TITLE = "extra_item_title"
        private const val TAG = "DetailActivity"
    }

    private lateinit var binding: ActivityDetailBinding
    private lateinit var vm: DetailViewModel
    private lateinit var seasonAdapter: SeasonTabAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    private var itemGuid: String = ""
    private var fallbackTitle: String = ""
    private var loadedDetail: ItemDetail? = null

    // 当前展示的剧集列表（用于点击集卡时把整队传给 PlayerActivity 做自动联播）
    private var currentEpisodes: List<Episode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        itemGuid = intent.getStringExtra(EXTRA_ITEM_GUID).orEmpty()
        fallbackTitle = intent.getStringExtra(EXTRA_ITEM_TITLE).orEmpty()
        if (itemGuid.isBlank()) {
            Toast.makeText(this, "缺少 itemGuid", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.titleText.text = fallbackTitle
        binding.loadingView.visibility = View.VISIBLE
        binding.errorText.visibility = View.GONE

        // 默认 Movie 模式按钮挂载点击
        binding.playButton.setOnClickListener { startPlay(itemGuid, fallbackTitle) }
        binding.tvBackButton.setOnClickListener { finish() }

        // 收藏 / 已看 按钮（两种模式都可见）
        binding.favoriteButton.setOnClickListener { vm.toggleFavorite(itemGuid) }
        binding.watchedButton.setOnClickListener { vm.toggleWatched(itemGuid) }

        // 季选择器（TV 多季用）
        seasonAdapter = SeasonTabAdapter { season ->
            Log.d(TAG, "Season selected: ${season.title}")
            vm.selectSeason(season.guid)
        }
        binding.seasonTabs.apply {
            layoutManager = LinearLayoutManager(this@DetailActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = seasonAdapter
        }

        // 剧集列表
        episodeAdapter = EpisodeAdapter(
            tokenProvider = { AccountStore.getToken() }
        ) { index, ep -> startPlay(ep.guid, ep.displayTitle, currentEpisodes, index) }
        binding.episodeList.apply {
            layoutManager = LinearLayoutManager(this@DetailActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = episodeAdapter
        }

        vm = ViewModelProvider(this)[DetailViewModel::class.java]
        vm.detail.observe(this) { d ->
            loadedDetail = d
            renderDetail(d)
            applyTypeMode(d.type)
        }
        vm.seasons.observe(this) { list ->
            if (list.size > 1) {
                // 多季：显示季 Tab
                binding.seasonTabs.visibility = View.VISIBLE
                seasonAdapter.submit(list)
            } else {
                // 单季或无季：不显示 Tab
                binding.seasonTabs.visibility = View.GONE
            }
        }
        vm.episodes.observe(this) { list ->
            currentEpisodes = list
            episodeAdapter.submit(list)
            if (list.isNotEmpty() && binding.episodesContainer.visibility == View.VISIBLE) {
                binding.episodeList.post {
                    val firstEpView = binding.episodeList.findViewHolderForAdapterPosition(0)?.itemView
                    firstEpView?.requestFocus()
                }
            }
        }
        vm.errorMessage.observe(this) { err ->
            Log.w(TAG, "load error: $err")
            binding.loadingView.visibility = View.GONE
            binding.errorText.text = "加载失败：$err"
            binding.errorText.visibility = View.VISIBLE
        }
        vm.loading.observe(this) { loading ->
            binding.loadingView.visibility = if (loading == true) View.VISIBLE else View.GONE
        }
        vm.isFavorite.observe(this) { fav ->
            binding.favoriteButton.text = getString(
                if (fav == true) R.string.detail_favorite_remove else R.string.detail_favorite_add
            )
        }
        vm.isWatched.observe(this) { watched ->
            binding.watchedButton.text = getString(
                if (watched == true) R.string.detail_watched_unmark else R.string.detail_watched_mark
            )
        }
        vm.toggleError.observe(this) { err ->
            if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
        }

        vm.load(itemGuid)
    }

    /** 根据 detail.type 切换 UI 模式 */
    private fun applyTypeMode(type: String) {
        val isTvLike = type == "TV" || type == "Season"
        if (isTvLike) {
            // TV 模式：隐藏 Movie 按钮区，显示剧集容器（topActionRow 始终可见）
            binding.movieButtonRow.visibility = View.GONE
            binding.episodesContainer.visibility = View.VISIBLE
        } else {
            // Movie / Video / Episode：显示 Play 按钮
            binding.movieButtonRow.visibility = View.VISIBLE
            binding.episodesContainer.visibility = View.GONE
            binding.seasonTabs.visibility = View.GONE
            binding.playButton.requestFocus()
        }
    }

    private fun renderDetail(d: ItemDetail) {
        binding.titleText.text = d.title.ifBlank { fallbackTitle }

        if (d.originalTitle.isNotBlank() && d.originalTitle != d.title) {
            binding.originalTitleText.text = d.originalTitle
            binding.originalTitleText.visibility = View.VISIBLE
        } else {
            binding.originalTitleText.visibility = View.GONE
        }

        binding.metaText.text = buildMeta(d)
        binding.overviewText.text = d.overview.ifBlank { getString(R.string.detail_no_overview) }

        val token = AccountStore.getToken()
        if (d.posterUrl.isNotBlank()) {
            Glide.with(this)
                .load(withAuth(d.posterUrl, token))
                .centerCrop()
                .placeholder(R.drawable.bg_poster_placeholder)
                .into(binding.posterImage)
        } else {
            Glide.with(this).clear(binding.posterImage)
        }

        val backdropSrc = if (d.backdropUrl.isNotBlank()) d.backdropUrl else d.posterUrl
        if (backdropSrc.isNotBlank()) {
            Glide.with(this)
                .load(withAuth(backdropSrc, token))
                .centerCrop()
                .into(binding.backdropImage)
        } else {
            Glide.with(this).clear(binding.backdropImage)
            binding.backdropImage.setBackgroundColor(0xFF1C1F24.toInt())
        }
    }

    private fun buildMeta(d: ItemDetail): String {
        val parts = mutableListOf<String>()
        val year = d.releaseDate.take(4)
        if (year.matches(Regex("\\d{4}"))) parts += year
        if (d.runtimeMinutes > 0) parts += getString(R.string.detail_runtime, d.runtimeMinutes)
        if (d.voteAverage > 0) parts += getString(R.string.detail_rating, d.voteAverage)
        if (d.resolutions.isNotEmpty()) parts += d.resolutions.joinToString("/")
        if (d.audioTypes.isNotEmpty()) parts += d.audioTypes.first()
        if (d.ancestorName.isNotBlank()) parts += d.ancestorName
        return parts.joinToString("  ·  ")
    }

    private fun withAuth(url: String, token: String): GlideUrl =
        if (token.isNotBlank()) {
            GlideUrl(
                url,
                LazyHeaders.Builder()
                    .addHeader("Authorization", token)
                    .addHeader("cookie", "mode=relay")
                    .build()
            )
        } else GlideUrl(url)

    /**
     * 启动播放页。
     * @param queue 联播队列：当点击的是某剧集列表中的一项时，传入完整 episode 列表，
     *              PlayerActivity 会在播放完成后自动切下一集。电影/单集/TV 入口传 null。
     * @param indexInQueue 当前 guid 在 queue 中的位置。
     */
    private fun startPlay(
        guid: String,
        title: String,
        queue: List<Episode>? = null,
        indexInQueue: Int = -1
    ) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_TOKEN, AccountStore.getToken())
            putExtra(PlayerActivity.EXTRA_ITEM_GUID, guid)
            if (queue != null && indexInQueue >= 0 && indexInQueue < queue.size) {
                putStringArrayListExtra(
                    PlayerActivity.EXTRA_EPISODE_GUIDS,
                    ArrayList(queue.map { it.guid })
                )
                putStringArrayListExtra(
                    PlayerActivity.EXTRA_EPISODE_TITLES,
                    ArrayList(queue.map { it.displayTitle })
                )
                putExtra(PlayerActivity.EXTRA_EPISODE_INDEX, indexInQueue)
            }
        }
        startActivity(intent)
    }
}
