package com.example.guet_map.core.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.guet_map.core.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications WHERE user_id = :userId ORDER BY created_at DESC")
    fun observeAll(userId: String): Flow<List<NotificationEntity>>

    @Query("SELECT COUNT(*) FROM notifications WHERE user_id = :userId AND is_read = 0")
    fun observeUnreadCount(userId: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<NotificationEntity>)

    @Query("UPDATE notifications SET is_read = 1 WHERE user_id = :userId")
    suspend fun markAllRead(userId: String)

    @Query("UPDATE notifications SET is_read = 1 WHERE id = :id AND user_id = :userId")
    suspend fun markRead(id: Long, userId: String)

    @Query("DELETE FROM notifications WHERE user_id = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("SELECT COUNT(*) FROM notifications WHERE user_id = :userId")
    suspend fun countByUserId(userId: String): Int

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()
}
