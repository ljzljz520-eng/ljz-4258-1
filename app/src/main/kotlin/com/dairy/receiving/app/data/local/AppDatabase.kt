package com.dairy.receiving.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [
        TripEntity::class,
        CompartmentEntity::class,
        SealEntity::class,
        SampleEntity::class,
        PipelineConnectionEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun compartmentDao(): CompartmentDao
    abstract fun sealDao(): SealDao
    abstract fun sampleDao(): SampleDao
    abstract fun pipelineDao(): PipelineDao

    /** 整车快照替换在一个事务内，断网恢复时不会出现半车状态。 */
    suspend fun replaceTrip(
        trip: TripEntity,
        compartments: List<CompartmentEntity>,
        seals: List<SealEntity>,
        samples: List<SampleEntity>,
        pipelines: List<PipelineConnectionEntity> = emptyList(),
    ) = withTransaction {
        tripDao().upsertTrip(trip)
        compartmentDao().clearForTrip(trip.tripId)
        compartmentDao().upsertAll(compartments)
        sealDao().clearForTrip(trip.tripId)
        sealDao().upsertAll(seals)
        sampleDao().clearForTrip(trip.tripId)
        sampleDao().upsertAll(samples)
        pipelineDao().clearForTrip(trip.tripId)
        pipelineDao().upsertAll(pipelines)
    }

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "receiving.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
