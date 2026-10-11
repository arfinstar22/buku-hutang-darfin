package com.bukuhutang.darfin.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bukuhutang.darfin.db.DebtDatabaseHelper
import com.bukuhutang.darfin.db.SyncTransactionDto
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

enum class SyncStatus {
    ONLINE,
    SYNCING,
    OFFLINE
}

interface SyncListener {
    fun onSyncStateChanged(status: SyncStatus, message: String)
    fun onDataUpdated(newTransactionsCount: Int)
}

object CloudSyncManager {
    private const val TAG = "CloudSyncManager"
    private const val PREFS_NAME = "buku_hutang_cloud_prefs"
    private const val KEY_SUPABASE_URL = "supabase_url"
    private const val KEY_SUPABASE_KEY = "supabase_key"

    // Default Supabase Cloud (Live & Active 24/7)
    const val DEFAULT_SUPABASE_URL = "https://fislizbynjisvwwdflaq.supabase.co"
    const val DEFAULT_SUPABASE_KEY = "sb_publishable_zp3e_lIVDWIQ9W8AKJ2kwg_35Mz9j3R"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<SyncListener>()

    private var isSyncing = false
    private var isPollingActive = false
    private var appContext: Context? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isPollingActive) {
                syncNow(appContext)
                mainHandler.postDelayed(this, 6000) // Poll every 6 seconds for real-time sync
            }
        }
    }

    fun getSupabaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SUPABASE_URL, DEFAULT_SUPABASE_URL) ?: DEFAULT_SUPABASE_URL
    }

    fun getSupabaseKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SUPABASE_KEY, DEFAULT_SUPABASE_KEY) ?: DEFAULT_SUPABASE_KEY
    }

    fun setSupabaseConfig(context: Context, url: String, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SUPABASE_URL, url.trim().removeSuffix("/"))
            .putString(KEY_SUPABASE_KEY, key.trim())
            .apply()
    }

    fun addListener(listener: SyncListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: SyncListener) {
        listeners.remove(listener)
    }

    fun startRealtimeSync(context: Context) {
        appContext = context.applicationContext
        if (!isPollingActive) {
            isPollingActive = true
            mainHandler.post(pollRunnable)
        }
    }

    fun stopRealtimeSync() {
        isPollingActive = false
        mainHandler.removeCallbacks(pollRunnable)
    }

    fun syncNow(context: Context?, onComplete: ((success: Boolean, newCount: Int) -> Unit)? = null) {
        if (isSyncing) return
        val ctx = context ?: appContext ?: return
        isSyncing = true
        notifyState(SyncStatus.SYNCING, "Sinkronisasi...")

        executor.execute {
            var success = false
            var newCount = 0

            try {
                val dbHelper = DebtDatabaseHelper(ctx)
                val baseUrl = getSupabaseUrl(ctx)
                val apiKey = getSupabaseKey(ctx)

                // 1. PUSH: Upload pending local transactions to Supabase PostgREST
                val pendingList = dbHelper.getPendingSyncTransactions()
                if (pendingList.isNotEmpty()) {
                    val uploadArray = JSONArray()
                    for (item in pendingList) {
                        val obj = JSONObject().apply {
                            put("uuid", item.uuid)
                            put("debtor_name", item.debtorName)
                            put("canonical_key", item.canonicalKey)
                            put("amount", item.amount)
                            put("type", item.type)
                            put("note", item.note)
                            put("created_at", item.createdAt)
                            put("timestamp", item.timestamp)
                        }
                        uploadArray.put(obj)
                    }

                    val postUrl = URL("$baseUrl/rest/v1/transactions")
                    val postConn = (postUrl.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 5000
                        readTimeout = 5000
                        doOutput = true
                        doInput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("apikey", apiKey)
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("Prefer", "resolution=merge-duplicates")
                    }

                    val writer = OutputStreamWriter(postConn.outputStream, "UTF-8")
                    writer.write(uploadArray.toString())
                    writer.flush()
                    writer.close()

                    val postCode = postConn.responseCode
                    if (postCode in 200..299) {
                        val uploadedUuids = pendingList.map { it.uuid }
                        dbHelper.markTransactionsSynced(uploadedUuids)
                    } else {
                        Log.e(TAG, "Post to Supabase failed code: $postCode")
                    }
                    postConn.disconnect()
                }

                // 2. PULL: Fetch recent transactions from Supabase (Delta Sync)
                val lastTimestamp = dbHelper.getLatestTransactionTimestamp()
                val minTimestamp = maxOf(0L, lastTimestamp - 60_000L) // 1 minute buffer for clock drift
                val getUrl = URL("$baseUrl/rest/v1/transactions?select=*&timestamp=gte.$minTimestamp&order=timestamp.asc")
                val getConn = (getUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("apikey", apiKey)
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }

                val getCode = getConn.responseCode
                if (getCode in 200..299) {
                    val reader = BufferedReader(InputStreamReader(getConn.inputStream, "UTF-8"))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    reader.close()

                    val remoteArray = JSONArray(sb.toString())
                    val remoteItems = mutableListOf<SyncTransactionDto>()

                    for (i in 0 until remoteArray.length()) {
                        val obj = remoteArray.getJSONObject(i)
                        remoteItems.add(
                            SyncTransactionDto(
                                uuid = obj.getString("uuid"),
                                debtorName = obj.getString("debtor_name"),
                                canonicalKey = obj.getString("canonical_key"),
                                amount = obj.getLong("amount"),
                                type = obj.getString("type"),
                                note = obj.optString("note", ""),
                                createdAt = obj.getString("created_at"),
                                timestamp = obj.getLong("timestamp")
                            )
                        )
                    }

                    newCount = dbHelper.mergeRemoteTransactions(remoteItems)
                    success = true

                    notifyState(SyncStatus.ONLINE, "Real-time Aktif")
                    if (newCount > 0 || pendingList.isNotEmpty()) {
                        notifyDataUpdated(newCount)
                    }
                } else {
                    Log.e(TAG, "Pull from Supabase failed code: $getCode")
                    notifyState(SyncStatus.OFFLINE, "Tersimpan Lokal")
                }
                getConn.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "Sync error: ${e.message}")
                notifyState(SyncStatus.OFFLINE, "Tersimpan Lokal")
            } finally {
                isSyncing = false
                mainHandler.post {
                    onComplete?.invoke(success, newCount)
                }
            }
        }
    }

    private fun notifyState(status: SyncStatus, msg: String) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onSyncStateChanged(status, msg)
            }
        }
    }

    private fun notifyDataUpdated(count: Int) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onDataUpdated(count)
            }
        }
    }
}
