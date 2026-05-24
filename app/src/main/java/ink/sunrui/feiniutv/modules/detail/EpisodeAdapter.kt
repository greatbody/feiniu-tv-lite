package ink.sunrui.feiniutv.modules.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import ink.sunrui.feiniutv.Episode
import ink.sunrui.feiniutv.R
import ink.sunrui.feiniutv.databinding.ItemEpisodeBinding

/**
 * 横排剧集卡适配器。
 *
 * 焦点视觉：与 PosterAdapter 同思路（描边 + 缩放）。
 * 点击 = 跳 PlayerActivity 播放该集。
 */
class EpisodeAdapter(
    private val tokenProvider: () -> String,
    private val onClick: (Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private val items = mutableListOf<Episode>()

    fun submit(list: List<Episode>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.episodeRoot.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                @Suppress("DEPRECATION")
                binding.epTitle.setTextColor(
                    v.resources.getColor(if (hasFocus) R.color.text_title else R.color.text_desc)
                )
            }
        }

        fun bind(e: Episode) {
            // 集号徽标
            binding.epNumberBadge.text = if (e.episodeNumber > 0) "EP ${e.episodeNumber.toString().padStart(2, '0')}" else "?"

            // 时长徽标
            if (e.duration > 0) {
                binding.epDurationBadge.text = formatDuration(e.duration)
                binding.epDurationBadge.visibility = View.VISIBLE
            } else {
                binding.epDurationBadge.visibility = View.GONE
            }

            // 标题
            binding.epTitle.text = e.displayTitle

            // 已看进度条：如果有部分观看进度且总时长已知
            if (e.watchedTs > 0 && e.duration > 0) {
                val pct = ((e.watchedTs.toDouble() / e.duration.toDouble()) * 1000).toInt().coerceIn(0, 1000)
                binding.epProgress.progress = pct
                binding.epProgress.visibility = View.VISIBLE
            } else {
                binding.epProgress.visibility = View.GONE
            }

            // 已看角标：整集看完时显示半透明黑底 + "✓ 已看"
            binding.epWatchedBadge.visibility = if (e.watched) View.VISIBLE else View.GONE

            // 缩略
            if (e.posterUrl.isNotBlank()) {
                val token = tokenProvider()
                val glideUrl = if (token.isNotBlank()) {
                    GlideUrl(
                        e.posterUrl,
                        LazyHeaders.Builder()
                            .addHeader("Authorization", token)
                            .addHeader("cookie", "mode=relay")
                            .build()
                    )
                } else GlideUrl(e.posterUrl)
                Glide.with(binding.episodeThumb.context)
                    .load(glideUrl)
                    .centerCrop()
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .into(binding.episodeThumb)
            } else {
                Glide.with(binding.episodeThumb.context).clear(binding.episodeThumb)
                binding.episodeThumb.setImageDrawable(null)
            }

            binding.episodeRoot.setOnClickListener { onClick(e) }
        }

        private fun formatDuration(seconds: Int): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%d:%02d", m, s)
        }
    }
}
