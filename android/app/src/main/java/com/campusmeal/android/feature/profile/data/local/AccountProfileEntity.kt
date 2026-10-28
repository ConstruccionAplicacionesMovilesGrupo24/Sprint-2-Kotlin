package com.campusmeal.android.feature.profile.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * The minimal `GET auth/me` answer kept for offline Home/Profile. [accountId] is the backend's stable
 * user `id`. Never holds tokens or passwords. [fetchedAtEpochMillis] is the UTC instant of the answer.
 */
@Entity(tableName = "account_profile")
data class AccountProfileEntity(
    @PrimaryKey val accountId: String,
    val name: String,
    val email: String,
    /** `USER` or `ANALYST` as received; presentation only, the backend stays authoritative. */
    val role: String,
    @ColumnInfo(name = "fetchedAt") val fetchedAtEpochMillis: Long,
)

@Dao
interface AccountProfileDao {

    @Upsert
    suspend fun upsert(profile: AccountProfileEntity)

    @Query("SELECT * FROM account_profile WHERE accountId = :accountId")
    suspend fun get(accountId: String): AccountProfileEntity?

    @Query("DELETE FROM account_profile WHERE accountId = :accountId")
    suspend fun delete(accountId: String)

    @Query("DELETE FROM account_profile WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}
