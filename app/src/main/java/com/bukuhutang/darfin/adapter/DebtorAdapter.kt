package com.bukuhutang.darfin.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bukuhutang.darfin.R
import com.bukuhutang.darfin.db.Debtor
import com.bukuhutang.darfin.parser.SmartDebtParser

class DebtorAdapter(
    private var list: List<Debtor>,
    private val onItemClick: (Debtor) -> Unit
) : RecyclerView.Adapter<DebtorAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvInitials: TextView = view.findViewById(R.id.tvAvatarInitials)
        val tvName: TextView = view.findViewById(R.id.tvDebtorName)
        val tvAmount: TextView = view.findViewById(R.id.tvDebtorAmount)
        val tvLastNote: TextView = view.findViewById(R.id.tvDebtorLastNote)
        val layoutBadge: LinearLayout = view.findViewById(R.id.layoutBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_debtor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.tvName.text = item.name

        // Initials avatar (e.g. "La Tanto" -> "LT", "Budi" -> "B")
        val words = item.name.trim().split("\\s+".toRegex())
        val initials = if (words.size >= 2) {
            "${words[0].take(1)}${words[1].take(1)}".uppercase()
        } else {
            item.name.take(2).uppercase()
        }
        holder.tvInitials.text = initials

        // Last note & transaction count (Crisp, never truncated)
        val note = item.lastNote.trim()
        val count = item.transactionCount
        val subtext = when {
            count > 0 && note.isNotEmpty() && !note.equals("Catatan hutang", ignoreCase = true) && !note.equals("Catatan transaksi", ignoreCase = true) ->
                "${count}x • $note"
            count > 0 ->
                "${count}x transaksi"
            note.isNotEmpty() ->
                note
            else ->
                "Belum ada catatan"
        }
        holder.tvLastNote.text = subtext

        val context = holder.itemView.context
        if (item.totalDebt > 0) {
            holder.tvAmount.text = SmartDebtParser.formatRupiah(item.totalDebt)
            holder.tvAmount.setTextColor(ContextCompat.getColor(context, R.color.debt_red))
            holder.layoutBadge.setBackgroundResource(R.drawable.bg_badge_debt)
        } else {
            holder.tvAmount.text = "✓ Lunas"
            holder.tvAmount.setTextColor(ContextCompat.getColor(context, R.color.paid_green))
            holder.layoutBadge.setBackgroundResource(R.drawable.bg_badge_paid)
        }

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = list.size

    fun updateData(newList: List<Debtor>) {
        list = newList
        notifyDataSetChanged()
    }
}
