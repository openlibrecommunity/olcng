package io.nekohasekai.sagernet.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "pool_registry")
data class PoolRegistry(
    @PrimaryKey val id: String = "builtin",
    val groupId: Long,
    val lastAttempt: Long = 0,
)

@Entity(tableName = "pool_sources")
data class PoolSource(
    @PrimaryKey val id: String,
    val url: String,
    val content: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastSuccess: Long = 0,
    val lastError: String? = null,
)

@Entity(tableName = "pool_memberships", primaryKeys = ["sourceId", "fingerprint"])
data class PoolMembership(
    val sourceId: String,
    val fingerprint: String,
    val profileId: Long,
)

@Dao
interface PoolDao {
    @Query("SELECT * FROM pool_registry WHERE id = 'builtin'")
    fun registry(): PoolRegistry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putRegistry(registry: PoolRegistry)

    @Query("SELECT * FROM pool_sources")
    fun sources(): List<PoolSource>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSources(sources: List<PoolSource>)

    @Query("DELETE FROM pool_memberships")
    fun clearMemberships()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putMemberships(memberships: List<PoolMembership>)

    @Query("SELECT * FROM pool_memberships WHERE profileId = :profileId")
    fun memberships(profileId: Long): List<PoolMembership>
}
