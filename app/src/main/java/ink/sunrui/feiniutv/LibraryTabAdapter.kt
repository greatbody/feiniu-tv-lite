package ink.sunrui.feiniutv

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 顶部媒体库 Tab 适配器：药丸按钮 + 焦点白底反色。
 *
 * 状态优先级（背景由 [R.drawable.bg_tab_selector] 处理）：
 *   focused  → 白底 + 黑字
 *   selected → 半透明灰底 + 白字
 *   normal   → 灰底 + 灰字
 *
 * 选中 vs 焦点：选中 = 当前正在展示的库；焦点 = 遥控器停在哪。
 * 两者解耦，所以遥控器向右移动浏览其它库不会立刻触发数据刷新。
 */
class LibraryTabAdapter(
    private val onSelected: (MediaLibrary) -> Unit
) : RecyclerView.Adapter<LibraryTabAdapter.TabViewHolder>() {

    private val items = mutableListOf<MediaLibrary>()
    private var selectedGuid: String? = null

    fun submitList(list: List<MediaLibrary>) {
        items.clear()
        items.addAll(list)
        if (selectedGuid == null && items.isNotEmpty()) {
            selectedGuid = items.first().guid
        }
        notifyDataSetChanged()
    }

    fun setSelected(guid: String?) {
        if (selectedGuid == guid) return
        selectedGuid = guid
        notifyDataSetChanged()
    }

    fun getSelectedPosition(): Int {
        val guid = selectedGuid ?: return 0
        return items.indexOfFirst { it.guid == guid }.coerceAtLeast(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return TabViewHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, item.guid == selectedGuid)
    }

    override fun getItemCount(): Int = items.size

    inner class TabViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
        fun bind(item: MediaLibrary, selected: Boolean) {
            // 标签：库名 + 条目数
            val countSuffix = if (item.itemCount > 0) "  ${item.itemCount}" else ""
            textView.text = "${item.name}$countSuffix"
            textView.isSelected = selected
            textView.setOnClickListener {
                setSelected(item.guid)
                onSelected(item)
            }
        }
    }
}
