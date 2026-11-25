package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO de Room con consultas parametrizadas y flujos reactivos `Flow<T>` para la UI Compose.
 */
@Dao
interface SyncRecordDao {

    @Query("SELECT * FROM sync_records ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecentRecords(limit: Int = 40): Flow<List<SyncRecordEntity>>

    @Query("SELECT * FROM sync_records WHERE synced = 0 ORDER BY createdAtMillis ASC")
    fun observePendingRecords(): Flow<List<SyncRecordEntity>>

    @Query("SELECT * FROM sync_records WHERE synced = 0 ORDER BY createdAtMillis ASC")
    suspend fun getPendingRecordsOnce(): List<SyncRecordEntity>

    @Query("SELECT COUNT(*) FROM sync_records WHERE synced = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_records WHERE synced = 1")
    fun observeSyncedCount(): Flow<Int>

    /**
     * Inserta ignorando duplicados si ya existe la misma `uniqueEventKey` o `eventId`.
     * Retorna -1L si el registro fue ignorado por duplicado.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreDuplicate(record: SyncRecordEntity): Long

    @Query(
        "UPDATE sync_records " +
            "SET synced = 1, syncedAtMillis = :syncedAtMillis, syncTarget = :syncTarget " +
            "WHERE eventId = :eventId"
    )
    suspend fun markAsSynced(eventId: String, syncedAtMillis: Long, syncTarget: String)

    @Query("UPDATE sync_records SET retryCount = retryCount + 1 WHERE eventId = :eventId")
    suspend fun incrementRetryCount(eventId: String)

    @Query("DELETE FROM sync_records WHERE synced = 1")
    suspend fun clearSyncedRecords()
}
