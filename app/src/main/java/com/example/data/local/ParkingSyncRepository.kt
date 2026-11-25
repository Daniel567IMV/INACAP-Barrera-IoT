package com.example.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Repositorio Room encargado de persistir eventos/telemetrías sin conexión con clave única
 * y sincronizar automáticamente los registros pendientes (`synced = false`) al reconectar,
 * evitando duplicados y sin perder registros ante fallos.
 */
class ParkingSyncRepository(
    private val syncRecordDao: SyncRecordDao
) {
    val recentRecords: Flow<List<SyncRecordEntity>> = syncRecordDao.observeRecentRecords(40)
    val pendingRecords: Flow<List<SyncRecordEntity>> = syncRecordDao.observePendingRecords()
    val pendingCount: Flow<Int> = syncRecordDao.observePendingCount()
    val syncedCount: Flow<Int> = syncRecordDao.observeSyncedCount()

    /**
     * Inserta un registro asegurando deduplicación por `uniqueEventKey`.
     * Retorna `true` si se insertó, `false` si ya existía (evitando duplicados).
     */
    suspend fun recordEvent(entity: SyncRecordEntity): Boolean = withContext(Dispatchers.IO) {
        val insertedRowId = syncRecordDao.insertIgnoreDuplicate(entity)
        insertedRowId != -1L
    }

    /**
     * Sincroniza todos los registros pendientes (`synced = false`) en orden cronológico FIFO.
     *
     * Si se configuró un endpoint HTTP/HTTPS válido, intenta primero sincronizar hacia ese
     * endpoint con cabecera `Idempotency-Key` (clave única) y token en cabecera `Authorization`;
     * en caso contrario (o en modo enlace directo OT), transmite sobre el canal inalámbrico activo
     * mediante `deliverViaSocket` y marca `synced = true` únicamente tras entrega confirmada.
     */
    suspend fun syncAllPending(
        configuredEndpointUrl: String,
        bearerToken: String,
        deliverViaSocket: suspend (SyncRecordEntity) -> Boolean
    ): SyncBatchSummary = withContext(Dispatchers.IO) {
        val pending = syncRecordDao.getPendingRecordsOnce()
        if (pending.isEmpty()) {
            return@withContext SyncBatchSummary(attempted = 0, synced = 0, failed = 0)
        }

        val normalizedUrl = validateSyncEndpointUrl(configuredEndpointUrl)
        var syncedNow = 0
        var failedNow = 0

        for (record in pending) {
            val deliveryResult = if (normalizedUrl != null) {
                val deliveredHttp = postRecordToHttpEndpoint(normalizedUrl, bearerToken, record)
                if (deliveredHttp) {
                    "HTTP:$normalizedUrl"
                } else {
                    // Fallback al canal socket OT si está disponible para no bloquear la cola
                    if (deliverViaSocket(record)) "SOCKET_OT_FALLBACK" else null
                }
            } else {
                if (deliverViaSocket(record)) "SOCKET_OT_CONFIRMED" else null
            }

            if (deliveryResult != null) {
                syncRecordDao.markAsSynced(
                    eventId = record.eventId,
                    syncedAtMillis = System.currentTimeMillis(),
                    syncTarget = deliveryResult
                )
                syncedNow++
            } else {
                // Nunca se borra un registro ante fallo: incrementa contador de reintentos
                syncRecordDao.incrementRetryCount(record.eventId)
                failedNow++
            }
        }

        SyncBatchSummary(
            attempted = pending.size,
            synced = syncedNow,
            failed = failedNow
        )
    }

    suspend fun clearSyncedHistory() = withContext(Dispatchers.IO) {
        syncRecordDao.clearSyncedRecords()
    }

    /**
     * Valida la URL de sincronización remota evitando esquemas inseguros (`file:`, `javascript:`, etc.).
     */
    fun validateSyncEndpointUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            if ((scheme == "https" || scheme == "http") && !host.isNullOrBlank()) {
                uri.toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun postRecordToHttpEndpoint(
        endpointUrl: String,
        bearerToken: String,
        record: SyncRecordEntity
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endpointUrl)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Idempotency-Key", record.uniqueEventKey)
                if (bearerToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }

            val body = JSONObject().apply {
                put("eventId", record.eventId)
                put("uniqueEventKey", record.uniqueEventKey)
                put("recordType", record.recordType)
                put("createdAtMillis", record.createdAtMillis)
                put("payload", record.payloadJson)
            }.toString()

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }

            val code = conn.responseCode
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    data class SyncBatchSummary(
        val attempted: Int,
        val synced: Int,
        val failed: Int
    )
}
