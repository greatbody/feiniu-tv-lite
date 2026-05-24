package ink.sunrui.feiniutv.modules.detail

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ink.sunrui.feiniutv.R
import ink.sunrui.feiniutv.Season

/**
 * 季选择 Tab 适配器（药丸样式，复用 item_tab.xml）。
 * 与 LibraryTabAdapter 同思路，但数据类型不同。
 */
class SeasonTabAdapter(
    private val onSelected: (Season) -> Unit
) : RecyclerView.Adapter<SeasonTabAdapter.VH>() {

    private val items = mutableListOf<Season>()
    private var selectedGuid: String? = null

    fun submit(list: List<Season>) {
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

    fun isEmpty(): Boolean = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return VH(view as TextView)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.bind(s, s.guid == selectedGuid)
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val text: TextView) : RecyclerView.ViewHolder(text) {
        fun bind(s: Season, selected: Boolean) {
            val countSuffix = if (s.episodeCount > 0) "  ${s.episodeCount}" else ""
            text.text = "${s.title}$countSuffix"
            text.isSelected = selected
            text.setOnClickListener {
                setSelected(s.guid)
                onSelected(s)
            }
        }
    }
}
