package com.bukuhutang.darfin

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bukuhutang.darfin.adapter.TransactionAdapter
import com.bukuhutang.darfin.db.DebtDatabaseHelper
import com.bukuhutang.darfin.db.DebtTransaction
import com.bukuhutang.darfin.parser.DebtType
import com.bukuhutang.darfin.parser.SmartDebtParser
import com.bukuhutang.darfin.sync.CloudSyncManager
import com.bukuhutang.darfin.sync.SyncListener
import com.bukuhutang.darfin.sync.SyncStatus

class DetailActivity : AppCompatActivity(), SyncListener {

    private lateinit var dbHelper: DebtDatabaseHelper
    private lateinit var txAdapter: TransactionAdapter
    private var contactId: Long = -1
    private var contactName: String = ""

    private lateinit var tvDetailTitle: TextView
    private lateinit var tvDetailRemainingDebt: TextView
    private lateinit var tvDetailSubtext: TextView
    private lateinit var rvTransactions: RecyclerView
    private lateinit var btnBack: ImageButton
    private lateinit var btnEditName: ImageButton
    private lateinit var layoutNameHeader: View
    private lateinit var btnSettleDebt: Button
    private lateinit var btnQuickPay: Button
    private lateinit var btnAddDebtForPerson: Button
    private lateinit var btnDeleteContact: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        contactId = intent.getLongExtra("CONTACT_ID", -1)
        contactName = intent.getStringExtra("CONTACT_NAME") ?: "Detail Hutang"

        dbHelper = DebtDatabaseHelper(this)

        initViews()
        setupListeners()
        CloudSyncManager.addListener(this)
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    override fun onDestroy() {
        super.onDestroy()
        CloudSyncManager.removeListener(this)
    }

    private fun initViews() {
        tvDetailTitle = findViewById(R.id.tvDetailTitle)
        tvDetailRemainingDebt = findViewById(R.id.tvDetailRemainingDebt)
        tvDetailSubtext = findViewById(R.id.tvDetailSubtext)
        rvTransactions = findViewById(R.id.rvTransactions)
        btnBack = findViewById(R.id.btnBack)
        btnEditName = findViewById(R.id.btnEditName)
        layoutNameHeader = findViewById(R.id.layoutNameHeader)
        btnSettleDebt = findViewById(R.id.btnSettleDebt)
        btnQuickPay = findViewById(R.id.btnQuickPay)
        btnAddDebtForPerson = findViewById(R.id.btnAddDebtForPerson)
        btnDeleteContact = findViewById(R.id.btnDeleteContact)

        tvDetailTitle.text = contactName
        txAdapter = TransactionAdapter(emptyList()) { tx ->
            showTransactionOptionsDialog(tx)
        }
        rvTransactions.layoutManager = LinearLayoutManager(this)
        rvTransactions.adapter = txAdapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnEditName.setOnClickListener {
            showEditNameDialog()
        }

        layoutNameHeader.setOnClickListener {
            showEditNameDialog()
        }

        btnSettleDebt.setOnClickListener {
            confirmSettleDebt()
        }

        btnQuickPay.setOnClickListener {
            showPaymentDialog()
        }

        btnAddDebtForPerson.setOnClickListener {
            showAddMoreDebtDialog()
        }

        btnDeleteContact.setOnClickListener {
            confirmDeleteContact()
        }
    }

    private fun loadData() {
        if (contactId == -1L) return

        val debtors = dbHelper.getAllDebtors()
        val debtor = debtors.find { it.id == contactId }

        val currentDebt = debtor?.totalDebt ?: 0L
        tvDetailRemainingDebt.text = SmartDebtParser.formatRupiah(currentDebt)

        val txList = dbHelper.getTransactionsForContact(contactId)
        tvDetailSubtext.text = "Total riwayat: ${txList.size} kali (ketuk untuk kelola)"
        txAdapter.updateData(txList)

        if (currentDebt == 0L) {
            btnSettleDebt.isEnabled = false
            btnSettleDebt.text = "✓ Sudah Lunas"
            btnSettleDebt.backgroundTintList = ContextCompat.getColorStateList(this, R.color.text_secondary)
            btnQuickPay.isEnabled = false
        } else {
            btnSettleDebt.isEnabled = true
            btnSettleDebt.text = "✓ Lunaskan Total (${SmartDebtParser.formatRupiah(currentDebt)})"
            btnSettleDebt.backgroundTintList = ContextCompat.getColorStateList(this, R.color.paid_green)
            btnQuickPay.isEnabled = true
        }
    }

    private fun confirmSettleDebt() {
        val debtors = dbHelper.getAllDebtors()
        val currentDebt = debtors.find { it.id == contactId }?.totalDebt ?: 0L

        if (currentDebt <= 0L) {
            Toast.makeText(this, "Hutang sudah lunas", Toast.LENGTH_SHORT).show()
            return
        }

        val formattedDebt = SmartDebtParser.formatRupiah(currentDebt)
        AlertDialog.Builder(this)
            .setTitle("Pelunasan Total")
            .setMessage("Lunaskan seluruh sisa hutang $formattedDebt untuk $contactName sekarang?")
            .setPositiveButton("Ya, Lunaskan") { _, _ ->
                dbHelper.recordPayment(contactId, currentDebt, "Pelunasan Total")
                Toast.makeText(this, "Hutang berhasil dilunasi!", Toast.LENGTH_SHORT).show()
                loadData()
                CloudSyncManager.syncNow(this)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showEditNameDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_name, null)
        val etEditName = dialogView.findViewById<EditText>(R.id.etEditName)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelEditName)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveEditName)

        etEditName.setText(contactName)
        etEditName.setSelection(contactName.length)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.65f)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val newName = etEditName.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(this, "Nama tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newName.equals(contactName, ignoreCase = false)) {
                dialog.dismiss()
                return@setOnClickListener
            }

            val success = dbHelper.updateContactName(contactId, newName)
            if (success) {
                contactName = newName
                tvDetailTitle.text = newName
                Toast.makeText(this, "Nama pelanggan berhasil diubah!", Toast.LENGTH_SHORT).show()
                loadData()
                dialog.dismiss()
                CloudSyncManager.syncNow(this)
            } else {
                Toast.makeText(this, "Gagal mengubah nama", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun confirmDeleteContact() {
        AlertDialog.Builder(this)
            .setTitle("Hapus Pelanggan?")
            .setMessage("Hapus $contactName beserta seluruh riwayat transaksinya dari buku hutang?\n\nData yang dihapus akan dibersihkan permanen.")
            .setPositiveButton("Hapus") { _, _ ->
                val key = dbHelper.deleteContact(contactId)
                if (key != null) {
                    CloudSyncManager.deleteRemoteContact(this, key)
                }
                Toast.makeText(this, "Pelanggan $contactName berhasil dihapus", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showPaymentDialog() {
        val debtors = dbHelper.getAllDebtors()
        val currentDebt = debtors.find { it.id == contactId }?.totalDebt ?: 0L

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_debt, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        tvTitle?.text = "Catat Cicilan - $contactName"

        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvDialogSubtitle)
        tvSubtitle?.text = "Sisa hutang: ${SmartDebtParser.formatRupiah(currentDebt)}. Ketik nominal (contoh: 20rb atau bensin lunas)."

        val etInput = dialogView.findViewById<EditText>(R.id.etInput)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        btnSave.text = "Simpan Pembayaran"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.65f)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Mohon isi nominal pembayaran", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val hasLunas = Regex("\\b(lunas|selesai|clear)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
            val explicitAmount = SmartDebtParser.parseRupiahAmount(text)
            val itemKeyword = text.replace("(?i)(rp\\.?\\s*)?\\d+[\\d.,]*\\s*(rb|k|ribu|jt|juta)?".toRegex(), "")
                .replace(Regex("\\b(lunas|selesai|clear|bayar|cicil|angsur|semua|total)\\b", RegexOption.IGNORE_CASE), "")
                .trim()

            if (explicitAmount > 0L) {
                val note = when {
                    hasLunas && itemKeyword.isNotEmpty() -> "Pelunasan $itemKeyword"
                    hasLunas -> "Pelunasan cicil"
                    itemKeyword.isNotEmpty() -> "Cicilan $itemKeyword"
                    else -> "Pembayaran cicil"
                }
                dbHelper.recordPayment(contactId, explicitAmount, note)
                Toast.makeText(this, "Pembayaran ${SmartDebtParser.formatRupiah(explicitAmount)} dicatat!", Toast.LENGTH_SHORT).show()
                loadData()
                dialog.dismiss()
                CloudSyncManager.syncNow(this)
                return@setOnClickListener
            }

            if (hasLunas) {
                if (itemKeyword.isNotEmpty()) {
                    val unpaidForItem = dbHelper.findUnpaidDebtForKeyword(contactId, itemKeyword)
                    if (unpaidForItem > 0L) {
                        dbHelper.recordPayment(contactId, unpaidForItem, "Pelunasan $itemKeyword")
                        Toast.makeText(this, "Hutang '$itemKeyword' (${SmartDebtParser.formatRupiah(unpaidForItem)}) berhasil dilunasi!", Toast.LENGTH_SHORT).show()
                        loadData()
                        dialog.dismiss()
                        CloudSyncManager.syncNow(this)
                        return@setOnClickListener
                    } else {
                        Toast.makeText(
                            this,
                            "Tidak ada hutang aktif dengan keterangan '$itemKeyword'. Sisa hutang: ${SmartDebtParser.formatRupiah(currentDebt)}",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                } else {
                    dbHelper.recordPayment(contactId, currentDebt, "Pelunasan Total")
                    Toast.makeText(this, "Seluruh sisa hutang berhasil dilunasi!", Toast.LENGTH_SHORT).show()
                    loadData()
                    dialog.dismiss()
                    CloudSyncManager.syncNow(this)
                    return@setOnClickListener
                }
            }

            Toast.makeText(this, "Ketik nominal atau keterangan (contoh: 20rb atau bensin lunas)", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showTransactionOptionsDialog(tx: DebtTransaction) {
        val formattedAmount = SmartDebtParser.formatRupiah(tx.amount)
        val actionName = when (tx.type) {
            DebtType.DEBT -> "Hutang (+$formattedAmount)"
            DebtType.PAYMENT -> "Pembayaran (-$formattedAmount)"
            DebtType.SETTLED -> "Pelunasan Total ($formattedAmount)"
            else -> "Transaksi ($formattedAmount)"
        }

        AlertDialog.Builder(this)
            .setTitle("Hapus Transaksi?")
            .setMessage("Hapus catatan transaksi ini dari riwayat $contactName?\n\n$actionName: \"${tx.note}\"\nTanggal: ${tx.createdAt}\n\nSisa total hutang akan otomatis dihitung ulang.")
            .setPositiveButton("Hapus") { _, _ ->
                val txUuid = dbHelper.deleteTransaction(tx.id, contactId)
                if (txUuid != null) {
                    CloudSyncManager.deleteRemoteTransaction(this, txUuid)
                }
                Toast.makeText(this, "Transaksi dihapus & sisa hutang diperbarui", Toast.LENGTH_SHORT).show()
                loadData()
                CloudSyncManager.syncNow(this)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showAddMoreDebtDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_debt, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        tvTitle?.text = "Tambah Hutang Baru - $contactName"

        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvDialogSubtitle)
        tvSubtitle?.text = "Ketik jumlah hutang yang ditambah (contoh: 50rb atau beras 100k)."

        val etInput = dialogView.findViewById<EditText>(R.id.etInput)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        btnSave.text = "Tambah Hutang"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.65f)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Mohon isi nominal hutang", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val amount = SmartDebtParser.parseRupiahAmount(text)
            if (amount > 0) {
                val note = text.replace("(?i)(rp\\.?\\s*)?\\d+[\\d.,]*\\s*(rb|k|ribu|jt|juta)?".toRegex(), "").trim()
                val noteFinal = if (note.isEmpty()) "Tambah hutang" else note
                dbHelper.recordAdditionalDebt(contactId, amount, noteFinal)
                Toast.makeText(this, "Hutang berhasil ditambah!", Toast.LENGTH_SHORT).show()
                loadData()
                dialog.dismiss()
                CloudSyncManager.syncNow(this)
            } else {
                Toast.makeText(this, "Nominal tidak valid. Contoh: 50rb", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    override fun onSyncStateChanged(status: SyncStatus, message: String) {}

    override fun onDataUpdated(newTransactionsCount: Int) {
        runOnUiThread {
            loadData()
        }
    }
}
