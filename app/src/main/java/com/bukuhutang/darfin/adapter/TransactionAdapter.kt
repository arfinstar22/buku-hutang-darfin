package com.bukuhutang.darfin.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bukuhutang.darfin.R
import com.bukuhutang.darfin.db.DebtTransaction
import com.bukuhutang.darfin.parser.DebtType
import com.bukuhutang.darfin.parser.SmartDebtParser

class TransactionAdapter(
    private var list: List<DebtTransaction>,
    private val onItemClick: ((DebtTransaction) -> Unit)? = null
) : RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvTxDate)
        val tvNote: TextView = view.findViewById(R.id.tvTxNote)
        val tvAmount: TextView = view.findViewById(R.id.tvTxAmount)
        val tvTypeTag: TextView = view.findViewById(R.id.tvTxTypeTag)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.tvDate.text = item.createdAt
        holder.tvNote.text = item.note.ifEmpty { "Catatan transaksi" }

        val formattedAmount = SmartDebtParser.formatRupiah(item.amount)
        when (item.type) {
            DebtType.DEBT -> {
                holder.tvTypeTag.text = "HUTANG"
                holder.tvTypeTag.setTextColor(Color.parseColor("#D32F2F"))
                holder.tvTypeTag.setBackgroundResource(R.drawable.bg_badge_debt)

                holder.tvAmount.text = "+$formattedAmount"
                holder.tvAmount.setTextColor(Color.parseColor("#D32F2F"))
            }
            DebtType.PAYMENT -> {
                holder.tvTypeTag.text = "BAYAR"
                holder.tvTypeTag.setTextColor(Color.parseColor("#00880E"))
                holder.tvTypeTag.setBackgroundResource(R.drawable.bg_badge_paid)

                holder.tvAmount.text = "-$formattedAmount"
                holder.tvAmount.setTextColor(Color.parseColor("#00880E"))
            }
            DebtType.SETTLED -> {
                holder.tvTypeTag.text = "LUNAS TOTAL"
                holder.tvTypeTag.setTextColor(Color.parseColor("#00880E"))
                holder.tvTypeTag.setBackgroundResource(R.drawable.bg_badge_paid)

                holder.tvAmount.text = "LUNAS"
                holder.tvAmount.setTextColor(Color.parseColor("#00880E"))
            }
        }

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(item)
        }
    }

    override fun getItemCount(): Int = list.size

    fun updateData(newList: List<DebtTransaction>) {
        list = newList
        notifyDataSetChanged()
    }
}
