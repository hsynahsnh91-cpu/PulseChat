package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.Contact
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE ownerUserId = :ownerUserId ORDER BY addedAt DESC")
    fun getContactsForUser(ownerUserId: String): Flow<List<Contact>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: Contact)

    @Query("DELETE FROM contacts WHERE ownerUserId = :ownerUserId AND targetUserId = :targetUserId")
    suspend fun removeContact(ownerUserId: String, targetUserId: String)

    @Query("UPDATE contacts SET isBlocked = :isBlocked WHERE ownerUserId = :ownerUserId AND targetUserId = :targetUserId")
    suspend fun setBlocked(ownerUserId: String, targetUserId: String, isBlocked: Boolean)
}
