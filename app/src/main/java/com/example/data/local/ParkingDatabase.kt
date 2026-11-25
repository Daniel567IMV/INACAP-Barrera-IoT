package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de datos SQLite local gestionada por AndroidX Room para cola offline y trazabilidad OT.
 */
@Database(
    entities = [SyncRecordEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ParkingDatabase : RoomDatabase() {

    abstract fun syncRecordDao(): SyncRecordDao

    companion object {
        private const val DATABASE_NAME = "inacap_parking_iot_es3.db"

        @Volatile
        private var INSTANCE: ParkingDatabase? = null

        fun getInstance(context: Context): ParkingDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ParkingDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
