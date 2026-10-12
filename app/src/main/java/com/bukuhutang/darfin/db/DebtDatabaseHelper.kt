package com.bukuhutang.darfin.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bukuhutang.darfin.parser.DebtType
import com.bukuhutang.darfin.parser.SmartDebtParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Debtor(
    val id: Long,
    val name: String,
    val canonicalKey: String,
    val totalDebt: Long,
    val lastNote: String,
    val transactionCount: Int = 0,
    val lastUpdated: Long = 0L
)

data class DebtTransaction(
    val id: Long,
    val uuid: String,
    val contactId: Long,
    val amount: Long,
    val type: DebtType,
    val note: String,
    val createdAt: String,
    val timestamp: Long,
    val syncStatus: Int
)

data class SyncTransactionDto(
    val uuid: String,
    val debtorName: String,
    val canonicalKey: String,
    val amount: Long,
    val type: String,
    val note: String,
    val createdAt: String,
    val timestamp: Long
)

class DebtDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "buku_hutang_darfin.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_CONTACTS = "contacts"
        const val COL_CONTACT_ID = "id"
        const val COL_CONTACT_NAME = "name"
        const val COL_CONTACT_KEY = "canonical_key"
        const val COL_CONTACT_TOTAL = "total_debt"
        const val COL_CONTACT_LAST_NOTE = "last_note"
        const val COL_CONTACT_UPDATED = "updated_at"

        const val TABLE_TRANSACTIONS = "transactions"
        const val COL_TX_ID = "id"
        const val COL_TX_UUID = "uuid"
        const val COL_TX_CONTACT_ID = "contact_id"
        const val COL_TX_AMOUNT = "amount"
        const val COL_TX_TYPE = "type"
        const val COL_TX_NOTE = "note"
        const val COL_TX_DATE = "created_at"
        const val COL_TX_TIMESTAMP = "tx_timestamp"
        const val COL_TX_SYNC_STATUS = "sync_status" // 0: pending, 1: synced
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_CONTACTS (
                $COL_CONTACT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONTACT_NAME TEXT NOT NULL,
                $COL_CONTACT_KEY TEXT UNIQUE NOT NULL,
                $COL_CONTACT_TOTAL INTEGER DEFAULT 0,
                $COL_CONTACT_LAST_NOTE TEXT,
                $COL_CONTACT_UPDATED INTEGER DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_TRANSACTIONS (
                $COL_TX_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TX_UUID TEXT UNIQUE NOT NULL,
                $COL_TX_CONTACT_ID INTEGER NOT NULL,
                $COL_TX_AMOUNT INTEGER NOT NULL,
                $COL_TX_TYPE TEXT NOT NULL,
                $COL_TX_NOTE TEXT,
                $COL_TX_DATE TEXT NOT NULL,
                $COL_TX_TIMESTAMP INTEGER NOT NULL,
                $COL_TX_SYNC_STATUS INTEGER DEFAULT 0,
                FOREIGN KEY($COL_TX_CONTACT_ID) REFERENCES $TABLE_CONTACTS($COL_CONTACT_ID)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COL_TX_UUID TEXT")
                db.execSQL("ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN $COL_TX_SYNC_STATUS INTEGER DEFAULT 0")
            } catch (_: Exception) {}
        }
    }

    fun processMessageInput(text: String): Boolean {
        val parsed = SmartDebtParser.parse(text) ?: return false
        val db = writableDatabase

        val key = parsed.canonicalKey
        var contactId: Long = -1
        var currentDebt: Long = 0

        val cursor = db.rawQuery("SELECT $COL_CONTACT_ID, $COL_CONTACT_TOTAL FROM $TABLE_CONTACTS WHERE $COL_CONTACT_KEY = ?", arrayOf(key))
        if (cursor.moveToFirst()) {
            contactId = cursor.getLong(0)
            currentDebt = cursor.getLong(1)
        }
        cursor.close()

        val isSettled = parsed.type == DebtType.SETTLED
        val rawKeyword = if (isSettled) {
            parsed.note.replace(Regex("(?i)\\b(lunas|selesai|clear)\\b|[()]"), "").trim()
        } else ""

        val matchedDebt = if (contactId != -1L && rawKeyword.isNotEmpty()) {
            findUnpaidDebtForKeyword(contactId, rawKeyword)
        } else 0L

        val amountToApply = when {
            isSettled && parsed.amount > 0L -> parsed.amount
            isSettled && rawKeyword.isNotEmpty() && matchedDebt > 0L -> matchedDebt
            isSettled && rawKeyword.isNotEmpty() -> 0L
            isSettled -> currentDebt
            else -> parsed.amount
        }

        val newDebt = when (parsed.type) {
            DebtType.DEBT -> currentDebt + amountToApply
            DebtType.PAYMENT -> maxOf(0L, currentDebt - amountToApply)
            DebtType.SETTLED -> {
                if (rawKeyword.isNotEmpty() && matchedDebt > 0L) {
                    maxOf(0L, currentDebt - matchedDebt)
                } else if (rawKeyword.isEmpty()) {
                    0L
                } else {
                    currentDebt
                }
            }
        }

        val noteText = when {
            isSettled && rawKeyword.isNotEmpty() -> "Pelunasan $rawKeyword"
            isSettled -> "Pelunasan Total"
            parsed.note.isNotEmpty() -> parsed.note
            parsed.type == DebtType.PAYMENT -> "Pembayaran cicil"
            else -> "Catatan hutang"
        }

        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val dateStr = dateFormat.format(Date(now))
        val txUuid = UUID.randomUUID().toString()

        if (contactId == -1L) {
            val cv = ContentValues().apply {
                put(COL_CONTACT_NAME, parsed.name)
                put(COL_CONTACT_KEY, key)
                put(COL_CONTACT_TOTAL, newDebt)
                put(COL_CONTACT_LAST_NOTE, noteText)
                put(COL_CONTACT_UPDATED, now)
            }
            contactId = db.insert(TABLE_CONTACTS, null, cv)
        } else {
            val cv = ContentValues().apply {
                put(COL_CONTACT_TOTAL, newDebt)
                put(COL_CONTACT_LAST_NOTE, noteText)
                put(COL_CONTACT_UPDATED, now)
            }
            db.update(TABLE_CONTACTS, cv, "$COL_CONTACT_ID = ?", arrayOf(contactId.toString()))
        }

        val txAmount = if (parsed.type == DebtType.SETTLED) amountToApply else parsed.amount
        val txType = if (isSettled && rawKeyword.isNotEmpty() && newDebt > 0L) {
            DebtType.PAYMENT.name
        } else {
            parsed.type.name
        }

        val txCv = ContentValues().apply {
            put(COL_TX_UUID, txUuid)
            put(COL_TX_CONTACT_ID, contactId)
            put(COL_TX_AMOUNT, txAmount)
            put(COL_TX_TYPE, txType)
            put(COL_TX_NOTE, noteText)
            put(COL_TX_DATE, dateStr)
            put(COL_TX_TIMESTAMP, now)
            put(COL_TX_SYNC_STATUS, 0) // pending sync to cloud
        }
        db.insert(TABLE_TRANSACTIONS, null, txCv)
        return true
    }

    fun recordPayment(contactId: Long, amount: Long, note: String) {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT $COL_CONTACT_TOTAL FROM $TABLE_CONTACTS WHERE $COL_CONTACT_ID = ?", arrayOf(contactId.toString()))
        var currentDebt = 0L
        if (cursor.moveToFirst()) {
            currentDebt = cursor.getLong(0)
        }
        cursor.close()

        val newDebt = maxOf(0L, currentDebt - amount)
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val dateStr = dateFormat.format(Date(now))
        val txUuid = UUID.randomUUID().toString()

        val cCv = ContentValues().apply {
            put(COL_CONTACT_TOTAL, newDebt)
            put(COL_CONTACT_LAST_NOTE, note)
            put(COL_CONTACT_UPDATED, now)
        }
        db.update(TABLE_CONTACTS, cCv, "$COL_CONTACT_ID = ?", arrayOf(contactId.toString()))

        val txCv = ContentValues().apply {
            put(COL_TX_UUID, txUuid)
            put(COL_TX_CONTACT_ID, contactId)
            put(COL_TX_AMOUNT, amount)
            put(COL_TX_TYPE, DebtType.PAYMENT.name)
            put(COL_TX_NOTE, note)
            put(COL_TX_DATE, dateStr)
            put(COL_TX_TIMESTAMP, now)
            put(COL_TX_SYNC_STATUS, 0)
        }
        db.insert(TABLE_TRANSACTIONS, null, txCv)
    }

    fun recordAdditionalDebt(contactId: Long, amount: Long, note: String) {
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT $COL_CONTACT_TOTAL FROM $TABLE_CONTACTS WHERE $COL_CONTACT_ID = ?", arrayOf(contactId.toString()))
        var currentDebt = 0L
        if (cursor.moveToFirst()) {
            currentDebt = cursor.getLong(0)
        }
        cursor.close()

        val newDebt = currentDebt + amount
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val dateStr = dateFormat.format(Date(now))
        val txUuid = UUID.randomUUID().toString()

        val cCv = ContentValues().apply {
            put(COL_CONTACT_TOTAL, newDebt)
            put(COL_CONTACT_LAST_NOTE, note)
            put(COL_CONTACT_UPDATED, now)
        }
        db.update(TABLE_CONTACTS, cCv, "$COL_CONTACT_ID = ?", arrayOf(contactId.toString()))

        val txCv = ContentValues().apply {
            put(COL_TX_UUID, txUuid)
            put(COL_TX_CONTACT_ID, contactId)
            put(COL_TX_AMOUNT, amount)
            put(COL_TX_TYPE, DebtType.DEBT.name)
            put(COL_TX_NOTE, note)
            put(COL_TX_DATE, dateStr)
            put(COL_TX_TIMESTAMP, now)
            put(COL_TX_SYNC_STATUS, 0)
        }
        db.insert(TABLE_TRANSACTIONS, null, txCv)
    }

    fun updateContactName(contactId: Long, newName: String): Boolean {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return false
        val newKey = SmartDebtParser.normalizeContactKey(cleanName)
        val db = writableDatabase

        // Check if another contact already has this key
        val check = db.rawQuery(
            "SELECT $COL_CONTACT_ID FROM $TABLE_CONTACTS WHERE $COL_CONTACT_KEY = ? AND $COL_CONTACT_ID != ?",
            arrayOf(newKey, contactId.toString())
        )
        val isDuplicate = check.moveToFirst()
        var targetId = -1L
        if (isDuplicate) {
            targetId = check.getLong(0)
        }
        check.close()

        if (isDuplicate && targetId != -1L) {
            // Merge transactions into targetId
            db.beginTransaction()
            try {
                val cvTx = ContentValues().apply {
                    put(COL_TX_CONTACT_ID, targetId)
                    put(COL_TX_SYNC_STATUS, 0)
                }
                db.update(TABLE_TRANSACTIONS, cvTx, "$COL_TX_CONTACT_ID = ?", arrayOf(contactId.toString()))
                db.delete(TABLE_CONTACTS, "$COL_CONTACT_ID = ?", arrayOf(contactId.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            recalculateAllDebts()
            return true
        }

        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put(COL_CONTACT_NAME, cleanName)
                put(COL_CONTACT_KEY, newKey)
                put(COL_CONTACT_UPDATED, System.currentTimeMillis())
            }
            db.update(TABLE_CONTACTS, cv, "$COL_CONTACT_ID = ?", arrayOf(contactId.toString()))

            // Mark transactions to re-sync with updated contact name
            val txCv = ContentValues().apply {
                put(COL_TX_SYNC_STATUS, 0)
            }
            db.update(TABLE_TRANSACTIONS, txCv, "$COL_TX_CONTACT_ID = ?", arrayOf(contactId.toString()))

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    fun deleteContact(contactId: Long): String? {
        val db = writableDatabase
        var canonicalKey: String? = null

        val cursor = db.rawQuery(
            "SELECT $COL_CONTACT_KEY FROM $TABLE_CONTACTS WHERE $COL_CONTACT_ID = ?",
            arrayOf(contactId.toString())
        )
        if (cursor.moveToFirst()) {
            canonicalKey = cursor.getString(0)
        }
        cursor.close()

        db.beginTransaction()
        try {
            db.delete(TABLE_TRANSACTIONS, "$COL_TX_CONTACT_ID = ?", arrayOf(contactId.toString()))
            db.delete(TABLE_CONTACTS, "$COL_CONTACT_ID = ?", arrayOf(contactId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return canonicalKey
    }

    fun findUnpaidDebtForKeyword(contactId: Long, keyword: String): Long {
        val cleanKey = keyword.trim().lowercase(Locale.ROOT)
        if (cleanKey.isEmpty()) return 0L

        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT $COL_TX_AMOUNT, $COL_TX_TYPE, $COL_TX_NOTE FROM $TABLE_TRANSACTIONS WHERE $COL_TX_CONTACT_ID = ? ORDER BY $COL_TX_TIMESTAMP ASC",
            arrayOf(contactId.toString())
        )

        var debtForKeyword = 0L
        var paidForKeyword = 0L

        if (cursor.moveToFirst()) {
            do {
                val amount = cursor.getLong(0)
                val typeStr = cursor.getString(1)
                val note = cursor.getString(2)?.lowercase(Locale.ROOT) ?: ""

                if (note.contains(cleanKey)) {
                    val type = DebtType.fromString(typeStr)
                    if (type == DebtType.DEBT) {
                        debtForKeyword += amount
                    } else if (type == DebtType.PAYMENT || type == DebtType.SETTLED) {
                        paidForKeyword += amount
                    }
                }
            } while (cursor.moveToNext())
        }
        cursor.close()

        return maxOf(0L, debtForKeyword - paidForKeyword)
    }

    fun deleteTransaction(txId: Long, contactId: Long): String? {
        val db = writableDatabase
        var txUuid: String? = null
        val c = db.rawQuery("SELECT $COL_TX_UUID FROM $TABLE_TRANSACTIONS WHERE $COL_TX_ID = ?", arrayOf(txId.toString()))
        if (c.moveToFirst()) {
            txUuid = c.getString(0)
        }
        c.close()

        db.beginTransaction()
        try {
            db.delete(TABLE_TRANSACTIONS, "$COL_TX_ID = ?", arrayOf(txId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        recalculateDebtForContact(contactId)
        return txUuid
    }

    fun recalculateDebtForContact(contactId: Long) {
        val db = writableDatabase
        val txCursor = db.rawQuery(
            "SELECT $COL_TX_AMOUNT, $COL_TX_TYPE, $COL_TX_NOTE, $COL_TX_TIMESTAMP FROM $TABLE_TRANSACTIONS WHERE $COL_TX_CONTACT_ID = ? ORDER BY $COL_TX_TIMESTAMP ASC",
            arrayOf(contactId.toString())
        )
        var balance = 0L
        var lastNote = ""
        var lastTime = 0L

        if (txCursor.moveToFirst()) {
            do {
                val amount = txCursor.getLong(0)
                val typeStr = txCursor.getString(1)
                val note = txCursor.getString(2) ?: ""
                val time = txCursor.getLong(3)

                lastNote = note
                lastTime = time

                when (DebtType.fromString(typeStr)) {
                    DebtType.DEBT -> balance += amount
                    DebtType.PAYMENT -> balance = maxOf(0L, balance - amount)
                    DebtType.SETTLED -> balance = if (amount > 0L) maxOf(0L, balance - amount) else 0L
                }
            } while (txCursor.moveToNext())
        }
        txCursor.close()

        val cv = ContentValues().apply {
            put(COL_CONTACT_TOTAL, balance)
            put(COL_CONTACT_LAST_NOTE, lastNote)
            put(COL_CONTACT_UPDATED, if (lastTime > 0L) lastTime else System.currentTimeMillis())
        }
        db.update(TABLE_CONTACTS, cv, "$COL_CONTACT_ID = ?", arrayOf(contactId.toString()))
    }

    fun getAllDebtors(filterMode: Int = 0, query: String = ""): List<Debtor> {
        val list = mutableListOf<Debtor>()
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT c.$COL_CONTACT_ID, c.$COL_CONTACT_NAME, c.$COL_CONTACT_KEY, c.$COL_CONTACT_TOTAL, 
                   c.$COL_CONTACT_LAST_NOTE, c.$COL_CONTACT_UPDATED,
                   (SELECT COUNT(*) FROM $TABLE_TRANSACTIONS t WHERE t.$COL_TX_CONTACT_ID = c.$COL_CONTACT_ID) as tx_count
            FROM $TABLE_CONTACTS c 
            ORDER BY c.$COL_CONTACT_TOTAL DESC, c.$COL_CONTACT_UPDATED DESC
        """.trimIndent(), null)

        val cleanQuery = query.trim().lowercase(Locale.getDefault())

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(0)
                val name = cursor.getString(1)
                val key = cursor.getString(2)
                val total = cursor.getLong(3)
                val lastNote = cursor.getString(4) ?: ""
                val updated = cursor.getLong(5)
                val txCount = cursor.getInt(6)

                // Filter logic
                val matchesFilter = when (filterMode) {
                    1 -> total > 0 // Unpaid only
                    2 -> total == 0L // Paid only
                    else -> true // All
                }

                val matchesQuery = cleanQuery.isEmpty() ||
                        name.lowercase(Locale.getDefault()).contains(cleanQuery) ||
                        key.contains(cleanQuery) ||
                        lastNote.lowercase(Locale.getDefault()).contains(cleanQuery)

                if (matchesFilter && matchesQuery) {
                    list.add(Debtor(id, name, key, total, lastNote, txCount, updated))
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getGrandTotalDebt(): Long {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT SUM($COL_CONTACT_TOTAL) FROM $TABLE_CONTACTS WHERE $COL_CONTACT_TOTAL > 0", null)
        var total = 0L
        if (cursor.moveToFirst()) {
            total = cursor.getLong(0)
        }
        cursor.close()
        return total
    }

    fun getDebtorCounts(): Triple<Int, Int, Int> {
        val db = readableDatabase
        var total = 0
        var unpaid = 0
        var paid = 0
        val cursor = db.rawQuery("SELECT $COL_CONTACT_TOTAL FROM $TABLE_CONTACTS", null)
        if (cursor.moveToFirst()) {
            do {
                total++
                val debt = cursor.getLong(0)
                if (debt > 0) unpaid++ else paid++
            } while (cursor.moveToNext())
        }
        cursor.close()
        return Triple(total, unpaid, paid)
    }

    fun getTransactionsForContact(contactId: Long): List<DebtTransaction> {
        val list = mutableListOf<DebtTransaction>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT $COL_TX_ID, $COL_TX_UUID, $COL_TX_CONTACT_ID, $COL_TX_AMOUNT, $COL_TX_TYPE, $COL_TX_NOTE, $COL_TX_DATE, $COL_TX_TIMESTAMP, $COL_TX_SYNC_STATUS " +
                    "FROM $TABLE_TRANSACTIONS WHERE $COL_TX_CONTACT_ID = ? ORDER BY $COL_TX_TIMESTAMP DESC, $COL_TX_ID DESC",
            arrayOf(contactId.toString())
        )
        if (cursor.moveToFirst()) {
            do {
                list.add(
                    DebtTransaction(
                        id = cursor.getLong(0),
                        uuid = cursor.getString(1),
                        contactId = cursor.getLong(2),
                        amount = cursor.getLong(3),
                        type = DebtType.fromString(cursor.getString(4)),
                        note = cursor.getString(5) ?: "",
                        createdAt = cursor.getString(6),
                        timestamp = cursor.getLong(7),
                        syncStatus = cursor.getInt(8)
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    // --- CLOUD SYNC METHODS ---

    fun getPendingSyncTransactions(): List<SyncTransactionDto> {
        val list = mutableListOf<SyncTransactionDto>()
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT t.$COL_TX_UUID, c.$COL_CONTACT_NAME, c.$COL_CONTACT_KEY, t.$COL_TX_AMOUNT, 
                   t.$COL_TX_TYPE, t.$COL_TX_NOTE, t.$COL_TX_DATE, t.$COL_TX_TIMESTAMP
            FROM $TABLE_TRANSACTIONS t
            JOIN $TABLE_CONTACTS c ON t.$COL_TX_CONTACT_ID = c.$COL_CONTACT_ID
            WHERE t.$COL_TX_SYNC_STATUS = 0
            ORDER BY t.$COL_TX_TIMESTAMP ASC
        """.trimIndent(), null)

        if (cursor.moveToFirst()) {
            do {
                list.add(
                    SyncTransactionDto(
                        uuid = cursor.getString(0),
                        debtorName = cursor.getString(1),
                        canonicalKey = cursor.getString(2),
                        amount = cursor.getLong(3),
                        type = cursor.getString(4),
                        note = cursor.getString(5) ?: "",
                        createdAt = cursor.getString(6),
                        timestamp = cursor.getLong(7)
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun getAllTransactionsForSync(): List<SyncTransactionDto> {
        val list = mutableListOf<SyncTransactionDto>()
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT t.$COL_TX_UUID, c.$COL_CONTACT_NAME, c.$COL_CONTACT_KEY, t.$COL_TX_AMOUNT, 
                   t.$COL_TX_TYPE, t.$COL_TX_NOTE, t.$COL_TX_DATE, t.$COL_TX_TIMESTAMP
            FROM $TABLE_TRANSACTIONS t
            JOIN $TABLE_CONTACTS c ON t.$COL_TX_CONTACT_ID = c.$COL_CONTACT_ID
            ORDER BY t.$COL_TX_TIMESTAMP ASC
        """.trimIndent(), null)

        if (cursor.moveToFirst()) {
            do {
                list.add(
                    SyncTransactionDto(
                        uuid = cursor.getString(0),
                        debtorName = cursor.getString(1),
                        canonicalKey = cursor.getString(2),
                        amount = cursor.getLong(3),
                        type = cursor.getString(4),
                        note = cursor.getString(5) ?: "",
                        createdAt = cursor.getString(6),
                        timestamp = cursor.getLong(7)
                    )
                )
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }

    fun markTransactionsSynced(uuids: List<String>) {
        if (uuids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply { put(COL_TX_SYNC_STATUS, 1) }
            for (uuid in uuids) {
                db.update(TABLE_TRANSACTIONS, cv, "$COL_TX_UUID = ?", arrayOf(uuid))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun mergeRemoteTransactions(remoteList: List<SyncTransactionDto>): Int {
        if (remoteList.isEmpty()) return 0
        val db = writableDatabase
        var newCount = 0

        db.beginTransaction()
        try {
            for (tx in remoteList) {
                // Check if uuid already exists
                val checkCursor = db.rawQuery("SELECT $COL_TX_ID FROM $TABLE_TRANSACTIONS WHERE $COL_TX_UUID = ?", arrayOf(tx.uuid))
                val exists = checkCursor.moveToFirst()
                checkCursor.close()

                if (!exists) {
                    // Find or create debtor
                    var contactId: Long = -1
                    val cCursor = db.rawQuery("SELECT $COL_CONTACT_ID FROM $TABLE_CONTACTS WHERE $COL_CONTACT_KEY = ?", arrayOf(tx.canonicalKey))
                    if (cCursor.moveToFirst()) {
                        contactId = cCursor.getLong(0)
                    }
                    cCursor.close()

                    if (contactId == -1L) {
                        val cCv = ContentValues().apply {
                            put(COL_CONTACT_NAME, tx.debtorName)
                            put(COL_CONTACT_KEY, tx.canonicalKey)
                            put(COL_CONTACT_TOTAL, 0L)
                            put(COL_CONTACT_LAST_NOTE, tx.note)
                            put(COL_CONTACT_UPDATED, tx.timestamp)
                        }
                        contactId = db.insert(TABLE_CONTACTS, null, cCv)
                    }

                    // Insert transaction as already synced
                    val txCv = ContentValues().apply {
                        put(COL_TX_UUID, tx.uuid)
                        put(COL_TX_CONTACT_ID, contactId)
                        put(COL_TX_AMOUNT, tx.amount)
                        put(COL_TX_TYPE, tx.type)
                        put(COL_TX_NOTE, tx.note)
                        put(COL_TX_DATE, tx.createdAt)
                        put(COL_TX_TIMESTAMP, tx.timestamp)
                        put(COL_TX_SYNC_STATUS, 1)
                    }
                    db.insert(TABLE_TRANSACTIONS, null, txCv)
                    newCount++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (newCount > 0) {
            recalculateAllDebts()
        }
        return newCount
    }

    fun recalculateAllDebts() {
        val db = writableDatabase
        val contactsCursor = db.rawQuery("SELECT $COL_CONTACT_ID FROM $TABLE_CONTACTS", null)
        val contactIds = mutableListOf<Long>()
        if (contactsCursor.moveToFirst()) {
            do {
                contactIds.add(contactsCursor.getLong(0))
            } while (contactsCursor.moveToNext())
        }
        contactsCursor.close()

        db.beginTransaction()
        try {
            for (cId in contactIds) {
                val txCursor = db.rawQuery(
                    "SELECT $COL_TX_AMOUNT, $COL_TX_TYPE, $COL_TX_NOTE, $COL_TX_TIMESTAMP FROM $TABLE_TRANSACTIONS WHERE $COL_TX_CONTACT_ID = ? ORDER BY $COL_TX_TIMESTAMP ASC",
                    arrayOf(cId.toString())
                )
                var balance = 0L
                var lastNote = ""
                var lastTime = 0L

                if (txCursor.moveToFirst()) {
                    do {
                        val amount = txCursor.getLong(0)
                        val typeStr = txCursor.getString(1)
                        val note = txCursor.getString(2) ?: ""
                        val time = txCursor.getLong(3)

                        lastNote = note
                        lastTime = time

                        when (DebtType.fromString(typeStr)) {
                            DebtType.DEBT -> balance += amount
                            DebtType.PAYMENT -> balance = maxOf(0L, balance - amount)
                            DebtType.SETTLED -> balance = if (amount > 0L) maxOf(0L, balance - amount) else 0L
                        }
                    } while (txCursor.moveToNext())
                }
                txCursor.close()

                val cv = ContentValues().apply {
                    put(COL_CONTACT_TOTAL, balance)
                    put(COL_CONTACT_LAST_NOTE, lastNote)
                    put(COL_CONTACT_UPDATED, lastTime)
                }
                db.update(TABLE_CONTACTS, cv, "$COL_CONTACT_ID = ?", arrayOf(cId.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getLatestTransactionTimestamp(): Long {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT MAX($COL_TX_TIMESTAMP) FROM $TABLE_TRANSACTIONS", null)
        var maxTime = 0L
        if (cursor.moveToFirst()) {
            maxTime = cursor.getLong(0)
        }
        cursor.close()
        return maxTime
    }
}
