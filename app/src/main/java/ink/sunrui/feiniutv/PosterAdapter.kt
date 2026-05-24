package ink.sunrui.feiniutv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import ink.sunrui.feiniutv.databinding.ItemPosterBinding

/**
 * 海报网格适配器（2:3 竖版海报 + 标题）。
 *
 * 焦点行为：
 *   - 边框由 [R.drawable.bg_poster_focus] 在 focused 时显示白色描边
 *   - 缩放由 OnFocusChangeListener 用 ViewPropertyAnimator 实现（API 12+）
 *   - 标题颜色随焦点：focused → 纯白；非焦点 → text_desc
 *   - 焦点时 elevation 抬升，让描边盖过相邻卡片
 */
class PosterAdapter(
    private val tokenProvider: () -> String,
    private val onClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<PosterAdapter.PosterViewHolder>() {

    private val items = mutableListOf<MediaItem>()
    private var pendingFocusPosition = -1
    private var attachedRecyclerView: RecyclerView? = null

    fun submitList(list: List<MediaItem>, restoreFocusPosition: Int = 0) {
        val safePos = if (list.isEmpty()) -1 else restoreFocusPosition.coerceIn(0, list.size - 1)
        pendingFocusPosition = safePos
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
        attachedRecyclerView?.post { tryRestoreFocus(safePos) }
    }

    private fun tryRestoreFocus(position: Int) {
        if (position < 0) return
        val rv = attachedRecyclerView ?: return
        val vh = rv.findViewHolderForAdapterPosition(position)
        if (vh != null) {
            vh.itemView.requestFocus()
            pendingFocusPosition = -1
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (attachedRecyclerView == recyclerView) attachedRecyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterViewHolder {
        val binding = ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PosterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PosterViewHolder, position: Int) {
        holder.bind(items[position])
        if (position == pendingFocusPosition) {
            holder.itemView.post { holder.itemView.requestFocus() }
            pendingFocusPosition = -1
        }
    }

    override fun getItemCount(): Int = items.size

    inner class PosterViewHolder(
        private val binding: ItemPosterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // 焦点视觉：缩放 + elevation 抬升
            binding.posterRoot.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.06f else 1.0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()
                // 焦点时让标题更亮
                binding.titleText.setTextColor(
                    v.resources.getColor(
                        if (hasFocus) R.color.text_title else R.color.text_desc
                    )
                )
            }
        }

        fun bind(item: MediaItem) {
            binding.titleText.text = item.title

            // 时长徽标：>0 才显示
            if (item.duration > 0) {
                binding.durationBadge.text = formatDuration(item.duration)
                binding.durationBadge.visibility = View.VISIBLE
            } else {
                binding.durationBadge.visibility = View.GONE
            }

            binding.root.setOnClickListener { onClick(item) }

            if (item.posterUrl.isNotBlank()) {
                // 飞牛 sys/img 端点需要 Authorization + cookie: mode=relay 才返回真图
                val token = tokenProvider()
                val glideUrl = if (token.isNotBlank()) {
                    GlideUrl(
                        item.posterUrl,
                        LazyHeaders.Builder()
                            .addHeader("Authorization", token)
                            .addHeader("cookie", "mode=relay")
                            .build()
                    )
                } else {
                    GlideUrl(item.posterUrl)
                }
                Glide.with(binding.posterImage.context)
                    .load(glideUrl)
                    .centerCrop()
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .into(binding.posterImage)
            } else {
                Glide.with(binding.posterImage.context).clear(binding.posterImage)
                binding.posterImage.setImageDrawable(null)
            }
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
