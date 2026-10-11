package com.bukuhutang.darfin

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bukuhutang.darfin.adapter.TransactionAdapter
import com.bukuhutang.darfin.db.DebtDatabaseHelper
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
    private lateinit var btnQuickPay: Button
    private lateinit var btnAddDebtForPerson: Button

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
        btnQuickPay = findViewById(R.id.btnQuickPay)
        btnAddDebtForPerson = findViewById(R.id.btnAddDebtForPerson)

        tvDetailTitle.text = contactName
        txAdapter = TransactionAdapter(emptyList())
        rvTransactions.layoutManager = LinearLayoutManager(this)
        rvTransactions.adapter = txAdapter
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnQuickPay.setOnClickListener {
            showPaymentDialog()
        }

        btnAddDebtForPerson.setOnClickListener {
            showAddMoreDebtDialog()
        }
    }

    private fun loadData() {
        if (contactId == -1L) return

        val debtors = dbHelper.getAllDebtors()
        val debtor = debtors.find { it.id == contactId }

        val currentDebt = debtor?.totalDebt ?: 0L
        tvDetailRemainingDebt.text = SmartDebtParser.formatRupiah(currentDebt)

        val txList = dbHelper.getTransactionsForContact(contactId)
        tvDetailSubtext.text = "Total riwayat: ${txList.size} kali"
        txAdapter.updateData(txList)

        if (currentDebt == 0L) {
            btnQuickPay.isEnabled = false
            btnQuickPay.text = "✓ Hutang Sudah Lunas"
        } else {
            btnQuickPay.isEnabled = true
            btnQuickPay.text = "✓ Catat Bayar"
        }
    }

    private fun showPaymentDialog() {
        val debtors = dbHelper.getAllDebtors()
        val currentDebt = debtors.find { it.id == contactId }?.totalDebt ?: 0L

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_debt, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
        tvTitle?.text = "Catat Pembayaran - $contactName"

        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvDialogSubtitle)
        tvSubtitle?.text = "Sisa hutang: ${SmartDebtParser.formatRupiah(currentDebt)}. Ketik nominal (contoh: 20rb atau lunas)."

        val etInput = dialogView.findViewById<EditText>(R.id.etInput)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        btnSave.text = "Simpan Pembayaran"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Mohon isi nominal pembayaran", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (text.equals("lunas", ignoreCase = true)) {
                dbHelper.recordPayment(contactId, currentDebt, "Pelunasan Total")
                Toast.makeText(this, "Hutang berhasil dilunasi!", Toast.LENGTH_SHORT).show()
                loadData()
                dialog.dismiss()
                CloudSyncManager.syncNow(this)
                return@setOnClickListener
            }

            val amount = SmartDebtParser.parseRupiahAmount(text)
            if (amount > 0) {
                dbHelper.recordPayment(contactId, amount, "Pembayaran cicil")
                Toast.makeText(this, "Pembayaran berhasil dicatat!", Toast.LENGTH_SHORT).show()
                loadData()
                dialog.dismiss()
                CloudSyncManager.syncNow(this)
            } else {
                Toast.makeText(this, "Nominal tidak valid. Contoh: 20rb atau 50000", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
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
