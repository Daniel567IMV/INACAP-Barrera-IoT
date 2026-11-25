package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Entidad Room (SQLite) para almacenamiento temporal sin conexión y sincronización confiable (ES3).
 *
 * Garantiza deduplicación mediante índice único `uniqueEventKey` + `@PrimaryKey eventId`
 * y rastrea el estado de sincronización mediante `synced: Boolean`.
 */
@Entity(
    tableName = "sync_records",
    indices = [
        Index(value = ["uniqueEventKey"], unique = true),
        Index(value = ["synced", "createdAtMillis"])
    ]
)
data class SyncRecordEntity(
    @PrimaryKey
    val eventId: String = UUID.randomUUID().toString(),
    val uniqueEventKey: String,
    val recordType: String,           // "TELEMETRIA", "COMANDO", "ACK", "ERR"
    val payloadJson: String,
    val distanceCm: Int? = null,
    val freeSlots: Int? = null,
    val totalCapacity: Int? = null,
    val parkingStatus: String? = null,
    val barrierAngle: Int? = null,
    val operationMode: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val synced: Boolean = false,
    val syncedAtMillis: Long? = null,
    val syncTarget: String? = null,
    val retryCount: Int = 0
) {
    fun formattedCreatedTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(createdAtMillis))
    }

    fun formattedShortTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(createdAtMillis))
    }
}
