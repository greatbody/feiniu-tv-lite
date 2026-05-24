package ink.sunrui.feiniutv.modules.server

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ink.sunrui.feiniutv.databinding.ItemServerBinding

/**
 * 设备列表适配器：mDNS 实时发现的 NAS。
 * 点击 = 选定该 NAS（由调用方写入 ServerStore + 跳 LoginActivity）。
 */
class ServerListAdapter(
    private val onClick: (ServerEntry) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.VH>() {

    private val items = mutableListOf<ServerEntry>()

    fun submit(list: List<ServerEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun upsert(entry: ServerEntry) {
        val existing = items.indexOfFirst { it.host == entry.host && it.port == entry.port }
        if (existing >= 0) {
            items[existing] = entry
            notifyItemChanged(existing)
        } else {
            items.add(entry)
            notifyItemInserted(items.size - 1)
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: ServerEntry) {
            binding.serverName.text = entry.displayName
            binding.serverAddress.text = entry.address
            binding.root.setOnClickListener { onClick(entry) }
        }
    }
}
