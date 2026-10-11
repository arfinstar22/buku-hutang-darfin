package com.bukuhutang.darfin

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bukuhutang.darfin.adapter.DebtorAdapter
import com.bukuhutang.darfin.db.DebtDatabaseHelper
import com.bukuhutang.darfin.parser.DebtType
import com.bukuhutang.darfin.parser.SmartDebtParser
import com.bukuhutang.darfin.sync.CloudSyncManager
import com.bukuhutang.darfin.sync.SyncListener
import com.bukuhutang.darfin.sync.SyncStatus
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity(), SyncListener {

    private lateinit var dbHelper: DebtDatabaseHelper
    private lateinit var debtorAdapter: DebtorAdapter

    private lateinit var tvGrandTotal: TextView
    private lateinit var tvDebtorCount: TextView
    private lateinit var tvSettledCount: TextView
    private lateinit var tvEmptyState: View
    private lateinit var etSearch: EditText
    private lateinit var rvDebtors: RecyclerView
    private lateinit var fabAdd: ExtendedFloatingActionButton

    private lateinit var tvSyncStatus: TextView
    private lateinit var dotSync: View
    private lateinit var btnRefreshSync: ImageView
    private lateinit var btnCloudSettings: ImageView

    private lateinit var btnFilterAll: TextView
    private lateinit var btnFilterUnpaid: TextView
    private lateinit var btnFilterPaid: TextView

    // Filter mode: 0 = All, 1 = Unpaid, 2 = Paid
    private var currentFilterMode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dbHelper = DebtDatabaseHelper(this)

        initViews()
        setupRecyclerView()
        setupListeners()
        setupFilters()
        CloudSyncManager.addListener(this)

        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
        CloudSyncManager.startRealtimeSync(this)
        CloudSyncManager.syncNow(this)
    }

    override fun onPause() {
        super.onPause()
        CloudSyncManager.stopRealtimeSync()
    }

    override fun onDestroy() {
        super.onDestroy()
        CloudSyncManager.removeListener(this)
    }

    private fun initViews() {
        tvGrandTotal = findViewById(R.id.tvGrandTotal)
        tvDebtorCount = findViewById(R.id.tvDebtorCount)
        tvSettledCount = findViewById(R.id.tvSettledCount)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        etSearch = findViewById(R.id.etSearch)
        rvDebtors = findViewById(R.id.rvDebtors)
        fabAdd = findViewById(R.id.fabAdd)

        tvSyncStatus = findViewById(R.id.tvSyncStatus)
        dotSync = findViewById(R.id.dotSync)
        btnRefreshSync = findViewById(R.id.btnRefreshSync)
        btnCloudSettings = findViewById(R.id.btnCloudSettings)

        btnFilterAll = findViewById(R.id.btnFilterAll)
        btnFilterUnpaid = findViewById(R.id.btnFilterUnpaid)
        btnFilterPaid = findViewById(R.id.btnFilterPaid)
    }

    private fun setupRecyclerView() {
        debtorAdapter = DebtorAdapter(emptyList()) { debtor ->
            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra("CONTACT_ID", debtor.id)
                putExtra("CONTACT_NAME", debtor.name)
            }
            startActivity(intent)
        }
        rvDebtors.layoutManager = LinearLayoutManager(this)
        rvDebtors.adapter = debtorAdapter
    }

    private fun setupFilters() {
        btnFilterAll.setOnClickListener {
            setFilter(0)
        }
        btnFilterUnpaid.setOnClickListener {
            setFilter(1)
        }
        btnFilterPaid.setOnClickListener {
            setFilter(2)
        }
    }

    private fun setFilter(mode: Int) {
        currentFilterMode = mode

        // Update pill appearances
        val white = ContextCompat.getColor(this, R.color.white)
        val textDark = ContextCompat.getColor(this, R.color.text_primary)

        btnFilterAll.setBackgroundResource(if (mode == 0) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        btnFilterAll.setTextColor(if (mode == 0) white else textDark)

        btnFilterUnpaid.setBackgroundResource(if (mode == 1) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        btnFilterUnpaid.setTextColor(if (mode == 1) white else textDark)

        btnFilterPaid.setBackgroundResource(if (mode == 2) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
        btnFilterPaid.setTextColor(if (mode == 2) white else textDark)

        loadData(etSearch.text.toString())
    }

    private fun setupListeners() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                loadData(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        fabAdd.setOnClickListener {
            showAddDebtDialog()
        }

        btnRefreshSync.setOnClickListener {
            Toast.makeText(this, "Menyinkronkan data...", Toast.LENGTH_SHORT).show()
            CloudSyncManager.syncNow(this) { success, newCount ->
                if (success) {
                    Toast.makeText(this, "Sinkronisasi berhasil! ($newCount data baru)", Toast.LENGTH_SHORT).show()
                    loadData(etSearch.text.toString())
                } else {
                    Toast.makeText(this, "Tidak dapat terhubung ke cloud", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnCloudSettings.setOnClickListener {
            showCloudSettingsDialog()
        }
    }

    private fun loadData(query: String = "") {
        val debtors = dbHelper.getAllDebtors(currentFilterMode, query)
        val grandTotal = dbHelper.getGrandTotalDebt()
        val (totalCount, unpaidCount, paidCount) = dbHelper.getDebtorCounts()

        tvGrandTotal.text = SmartDebtParser.formatRupiah(grandTotal)
        tvDebtorCount.text = "$unpaidCount belum lunas"
        tvSettledCount.text = "$paidCount lunas"

        btnFilterAll.text = "Semua ($totalCount)"
        btnFilterUnpaid.text = "Belum Lunas ($unpaidCount)"
        btnFilterPaid.text = "Lunas ($paidCount)"

        if (debtors.isEmpty()) {
            tvEmptyState.visibility = View.VISIBLE
            rvDebtors.visibility = View.GONE
        } else {
            tvEmptyState.visibility = View.GONE
            rvDebtors.visibility = View.VISIBLE
            debtorAdapter.updateData(debtors)
        }
    }

    // --- SyncListener Implementation ---

    override fun onSyncStateChanged(status: SyncStatus, message: String) {
        runOnUiThread {
            when (status) {
                SyncStatus.ONLINE -> {
                    tvSyncStatus.text = "Real-time Aktif"
                    dotSync.setBackgroundResource(R.drawable.bg_dot_green)
                }
                SyncStatus.SYNCING -> {
                    tvSyncStatus.text = "Sinkronisasi..."
                    dotSync.setBackgroundResource(R.drawable.bg_dot_yellow)
                }
                SyncStatus.OFFLINE -> {
                    tvSyncStatus.text = "Tersimpan Lokal"
                    dotSync.setBackgroundResource(R.drawable.bg_dot_gray)
                }
            }
        }
    }

    override fun onDataUpdated(newTransactionsCount: Int) {
        loadData(etSearch.text.toString())
    }

    // --- Dialogs ---

    private fun showAddDebtDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_debt, null)
        val etInput = dialogView.findViewById<EditText>(R.id.etInput)
        val tvPreview = dialogView.findViewById<TextView>(R.id.tvPreview)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setDimAmount(0.65f)

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                val text = s?.toString()?.trim() ?: ""
                val parsed = SmartDebtParser.parse(text)
                if (parsed != null) {
                    val formatted = SmartDebtParser.formatRupiah(parsed.amount)
                    val actionName = when (parsed.type) {
                        DebtType.DEBT -> "Tambah Hutang: +$formatted"
                        DebtType.PAYMENT -> "Pembayaran: -$formatted"
                        DebtType.SETTLED -> "Pelunasan Total: LUNAS"
                    }
                    var preview = "👤 ${parsed.name} | $actionName"
                    if (parsed.note.isNotEmpty()) {
                        preview += "\n📝 Catatan: ${parsed.note}"
                    }
                    tvPreview.text = preview
                } else {
                    tvPreview.text = "Ketik nama dan nominal (contoh: budi 50rb)"
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Mohon isi catatan hutang", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val success = dbHelper.processMessageInput(text)
            if (success) {
                Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                loadData(etSearch.text.toString())
                dialog.dismiss()
                // Sync immediately to cloud so other phone receives it!
                CloudSyncManager.syncNow(this)
            } else {
                Toast.makeText(this, getString(R.string.toast_invalid), Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
    }

    private fun showCloudSettingsDialog() {
        val currentUrl = CloudSyncManager.getSupabaseUrl(this)
        val currentKey = CloudSyncManager.getSupabaseKey(this)
        val currentSecret = CloudSyncManager.getSyncSecret(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        val tvUrlLabel = TextView(this).apply { text = "Supabase Project URL:" }
        val etUrl = EditText(this).apply {
            setText(currentUrl)
            hint = "https://xxx.supabase.co"
        }

        val tvKeyLabel = TextView(this).apply {
            text = "Anon API Key:"
            setPadding(0, 20, 0, 0)
        }
        val etKey = EditText(this).apply {
            setText(currentKey)
            hint = "sb_publishable_..."
        }

        val tvSecretLabel = TextView(this).apply {
            text = "Kunci Rahasia Sync (PIN / Secret):"
            setPadding(0, 20, 0, 0)
        }
        val etSecret = EditText(this).apply {
            setText(currentSecret)
            hint = "darfin_sync_secure_2026"
        }

        layout.addView(tvUrlLabel)
        layout.addView(etUrl)
        layout.addView(tvKeyLabel)
        layout.addView(etKey)
        layout.addView(tvSecretLabel)
        layout.addView(etSecret)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cloud_settings_title))
            .setMessage("Konfigurasi Cloud Supabase (Sinkronisasi HP Tuan Darfin & HP Ibu):")
            .setView(layout)
            .setPositiveButton("Simpan & Sinkron") { _, _ ->
                val newUrl = etUrl.text.toString().trim()
                val newKey = etKey.text.toString().trim()
                val newSecret = etSecret.text.toString().trim()
                val validSecret = if (newSecret.isNotEmpty()) newSecret else CloudSyncManager.DEFAULT_SYNC_SECRET
                if (newUrl.isNotEmpty() && newKey.isNotEmpty()) {
                    CloudSyncManager.setSupabaseConfig(this, newUrl, newKey, validSecret)
                    Toast.makeText(this, "Pengaturan disimpan. Menghubungkan...", Toast.LENGTH_SHORT).show()
                    CloudSyncManager.syncNow(this) { success, _ ->
                        if (success) {
                            Toast.makeText(this, "Berhasil terhubung ke Supabase!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Gagal terhubung ke Supabase", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
