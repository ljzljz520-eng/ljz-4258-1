package com.dairy.receiving.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrip(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE tripId = :tripId")
    suspend fun getTrip(tripId: String): TripEntity?

    @Query("SELECT * FROM trips ORDER BY updatedAt DESC")
    fun observeTrips(): Flow<List<TripEntity>>

    @Query("DELETE FROM trips WHERE tripId = :tripId")
    suspend fun deleteTrip(tripId: String)
}

@Dao
interface CompartmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CompartmentEntity>)

    @Query("SELECT * FROM compartments WHERE tripId = :tripId ORDER BY code")
    fun observeForTrip(tripId: String): Flow<List<CompartmentEntity>>

    @Query("DELETE FROM compartments WHERE tripId = :tripId")
    suspend fun clearForTrip(tripId: String)
}

@Dao
interface SealDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SealEntity>)

    @Query("SELECT * FROM seals WHERE tripId = :tripId")
    suspend fun forTrip(tripId: String): List<SealEntity>

    @Query("DELETE FROM seals WHERE tripId = :tripId")
    suspend fun clearForTrip(tripId: String)
}

@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE tripId = :tripId")
    fun observeForTrip(tripId: String): Flow<List<SampleEntity>>

    @Query("DELETE FROM samples WHERE tripId = :tripId")
    suspend fun clearForTrip(tripId: String)
}

@Dao
interface PipelineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PipelineConnectionEntity>)

    @Query("SELECT * FROM pipeline_connections WHERE tripId = :tripId")
    fun observeForTrip(tripId: String): Flow<List<PipelineConnectionEntity>>

    @Query("DELETE FROM pipeline_connections WHERE tripId = :tripId")
    suspend fun clearForTrip(tripId: String)
}
