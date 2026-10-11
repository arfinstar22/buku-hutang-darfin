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
    private const val KEY_SERVER_URL = "server_url"
    
    // Default fallback demo server URL (can be changed to Render or Supabase)
    const val DEFAULT_SERVER_URL = "http://10.0.2.2:3000"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<SyncListener>()

    private var isSyncing = false
    private var isPollingActive = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isPollingActive) {
                syncNow(null)
                mainHandler.postDelayed(this, 6000) // Poll every 6 seconds
            }
        }
    }

    fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun setServerUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVER_URL, url.trim().removeSuffix("/")).apply()
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
        isSyncing = true
        notifyState(SyncStatus.SYNCING, "Sinkronisasi...")

        executor.execute {
            var success = false
            var newCount = 0

            try {
                val dbHelper = DebtDatabaseHelper(context ?: return@execute)
                val targetUrl = getServerUrl(context) + "/api/sync"

                // 1. Prepare pending transactions to upload
                val pendingList = dbHelper.getPendingSyncTransactions()
                val pendingArray = JSONArray()
                for (item in pendingList) {
                    val obj = JSONObject().apply {
                        put("uuid", item.uuid)
                        put("debtorName", item.debtorName)
                        put("canonicalKey", item.canonicalKey)
                        put("amount", item.amount)
                        put("type", item.type)
                        put("note", item.note)
                        put("createdAt", item.createdAt)
                        put("timestamp", item.timestamp)
                    }
                    pendingArray.put(obj)
                }

                val payload = JSONObject().apply {
                    put("transactions", pendingArray)
                }

                // 2. HTTP POST to Cloud Sync Server
                val url = URL(targetUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 4000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                }

                val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
                writer.write(payload.toString())
                writer.flush()
                writer.close()

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    reader.close()

                    val resJson = JSONObject(sb.toString())
                    val remoteArray = resJson.optJSONArray("allTransactions") ?: JSONArray()
                    val remoteItems = mutableListOf<SyncTransactionDto>()

                    for (i in 0 until remoteArray.length()) {
                        val obj = remoteArray.getJSONObject(i)
                        remoteItems.add(
                            SyncTransactionDto(
                                uuid = obj.getString("uuid"),
                                debtorName = obj.getString("debtorName"),
                                canonicalKey = obj.getString("canonicalKey"),
                                amount = obj.getLong("amount"),
                                type = obj.getString("type"),
                                note = obj.optString("note", ""),
                                createdAt = obj.getString("createdAt"),
                                timestamp = obj.getLong("timestamp")
                            )
                        )
                    }

                    // Mark uploaded ones as synced
                    val uploadedUuids = pendingList.map { it.uuid }
                    dbHelper.markTransactionsSynced(uploadedUuids)

                    // Merge remote items
                    newCount = dbHelper.mergeRemoteTransactions(remoteItems)
                    success = true

                    notifyState(SyncStatus.ONLINE, "Real-time Aktif")
                    if (newCount > 0 || pendingList.isNotEmpty()) {
                        notifyDataUpdated(newCount)
                    }
                } else {
                    notifyState(SyncStatus.OFFLINE, "Tersimpan Lokal")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "Sync offline or network unreachable: ${e.message}")
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
