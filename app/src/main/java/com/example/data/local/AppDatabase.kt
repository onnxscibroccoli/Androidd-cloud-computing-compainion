package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [ComputeJobEntity::class, TelemetryLogEntity::class],
  version = 3,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun computeJobDao(): ComputeJobDao
  abstract fun telemetryDao(): TelemetryDao

  companion object {
    private val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          "ALTER TABLE compute_jobs ADD COLUMN isSimulatedCloud INTEGER NOT NULL DEFAULT 1"
        )
      }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          "ALTER TABLE compute_jobs ADD COLUMN localCpuMeasured INTEGER NOT NULL DEFAULT 0"
        )
      }
    }

    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          AppDatabase::class.java,
          "oci_controller_db"
        )
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
          .fallbackToDestructiveMigration(true)
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
