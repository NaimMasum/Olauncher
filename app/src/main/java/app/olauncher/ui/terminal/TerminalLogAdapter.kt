package app.olauncher.ui.terminal

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.data.AppModel
import app.olauncher.databinding.ItemTerminalLogBinding

class TerminalLogAdapter(
    private var theme: TerminalTheme,
    private val onAppClicked: (AppModel.App) -> Unit,
    private val onItemClicked: (CharSequence) -> Unit
) : RecyclerView.Adapter<TerminalLogAdapter.ViewHolder>() {

    private val items = ArrayList<TerminalLogItem>()

    fun setTheme(newTheme: TerminalTheme) {
        this.theme = newTheme
        notifyDataSetChanged()
    }

    fun addItem(item: TerminalLogItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun addItems(newItems: List<TerminalLogItem>) {
        val startPos = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(startPos, newItems.size)
    }

    fun clear() {
        val count = items.size
        items.clear()
        notifyItemRangeRemoved(0, count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTerminalLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemTerminalLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TerminalLogItem) {
            binding.tvTerminalLog.text = item.text
            val color = when (item.type) {
                TerminalItemType.COMMAND_ECHO -> theme.commandColor
                TerminalItemType.ERROR -> theme.errorColor
                TerminalItemType.BANNER -> theme.accentColor
                TerminalItemType.APP_ENTRY -> theme.promptColor
                TerminalItemType.SUCCESS -> theme.promptColor
                TerminalItemType.OUTPUT -> theme.textColor
                TerminalItemType.NOTIFICATION -> theme.textColor
            }
            binding.tvTerminalLog.setTextColor(color)

            binding.root.setOnClickListener {
                if (item.appModel != null) {
                    onAppClicked(item.appModel)
                } else {
                    onItemClicked(item.text)
                }
            }
        }
    }
}
