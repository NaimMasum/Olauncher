package app.olauncher.ui.terminal

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.databinding.ItemTerminalSuggestionBinding

data class TerminalSuggestion(
    val displayText: String,
    val commandToFill: String,
    val executeImmediately: Boolean = false,
    val isAction: Boolean = false,
    val isPinnedApp: Boolean = false
)

class TerminalSuggestionAdapter(
    private var theme: TerminalTheme,
    private val onSuggestionClicked: (TerminalSuggestion) -> Unit,
    private val onSuggestionLongClicked: ((TerminalSuggestion) -> Boolean)? = null
) : RecyclerView.Adapter<TerminalSuggestionAdapter.ViewHolder>() {

    private val suggestions = ArrayList<TerminalSuggestion>()

    fun setTheme(newTheme: TerminalTheme) {
        this.theme = newTheme
        notifyDataSetChanged()
    }

    fun setSuggestions(newSuggestions: List<TerminalSuggestion>) {
        suggestions.clear()
        suggestions.addAll(newSuggestions)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTerminalSuggestionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(suggestions[position])
    }

    override fun getItemCount(): Int = suggestions.size

    inner class ViewHolder(private val binding: ItemTerminalSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(suggestion: TerminalSuggestion) {
            binding.tvSuggestion.text = suggestion.displayText

            if (suggestion.isAction) {
                binding.tvSuggestion.setTextColor(theme.promptColor)
                val background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f
                    setColor((theme.promptColor and 0x00FFFFFF) or 0x22000000)
                    setStroke(2, theme.promptColor)
                }
                binding.tvSuggestion.background = background
            } else if (suggestion.isPinnedApp) {
                binding.tvSuggestion.setTextColor(theme.textColor)
                val background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f
                    setColor((theme.promptColor and 0x00FFFFFF) or 0x18000000)
                    setStroke(1, theme.promptColor)
                }
                binding.tvSuggestion.background = background
            } else {
                binding.tvSuggestion.setTextColor(theme.textColor)
                val background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f
                    setColor((theme.secondaryColor and 0x00FFFFFF) or 0x33000000)
                    setStroke(1, theme.secondaryColor)
                }
                binding.tvSuggestion.background = background
            }

            binding.root.setOnClickListener {
                onSuggestionClicked(suggestion)
            }

            binding.root.setOnLongClickListener {
                onSuggestionLongClicked?.invoke(suggestion) ?: false
            }
        }
    }
}
