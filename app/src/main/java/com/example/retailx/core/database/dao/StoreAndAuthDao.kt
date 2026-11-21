package com.example.retailx.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.example.retailx.core.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreAndAuthDao {
    @Query("SELECT * FROM stores LIMIT 1")
    fun observeStore(): Flow<StoreEntity?>

    @Query("SELECT * FROM stores LIMIT 1")
    suspend fun getStore(): StoreEntity?

    @Upsert
    suspend fun upsertStore(store: StoreEntity)

    @Query("SELECT * FROM users ORDER BY created_at ASC")
    fun observeUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users ORDER BY created_at ASC")
    suspend fun getAllUsers(): List<UserEntity>

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getUserByUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getUserById(id: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: UserEntity)

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("SELECT * FROM roles ORDER BY name ASC")
    fun observeRoles(): Flow<List<RoleEntity>>

    @Query("SELECT * FROM roles ORDER BY name ASC")
    suspend fun getAllRoles(): List<RoleEntity>

    @Query("SELECT * FROM roles WHERE id = :id LIMIT 1")
    suspend fun getRoleById(id: String): RoleEntity?

    @Upsert
    suspend fun insertRoles(roles: List<RoleEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPermissions(permissions: List<PermissionEntity>)

    @Query("SELECT * FROM permissions")
    suspend fun getAllPermissions(): List<PermissionEntity>

    @Upsert
    suspend fun insertUserRole(userRole: UserRoleEntity)

    @Query("SELECT * FROM user_roles")
    fun observeUserRoles(): Flow<List<UserRoleEntity>>

    @Query("SELECT r.* FROM roles r INNER JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = :userId LIMIT 1")
    suspend fun getRoleForUser(userId: String): RoleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRolePermissions(items: List<RolePermissionEntity>)

    @Query("DELETE FROM role_permissions WHERE role_id = :roleId")
    suspend fun deleteRolePermissions(roleId: String)

    @Query("SELECT * FROM role_permissions")
    fun observeRolePermissions(): Flow<List<RolePermissionEntity>>

    @Query(
        "SELECT p.code FROM permissions p " +
            "INNER JOIN role_permissions rp ON p.id = rp.permission_id " +
            "WHERE rp.role_id = :roleId"
    )
    suspend fun getPermissionCodesForRole(roleId: String): List<String>

    @Query("SELECT * FROM devices ORDER BY paired_at DESC")
    fun observeDevices(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE id = :id LIMIT 1")
    suspend fun getDeviceById(id: String): DeviceEntity?

    @Upsert
    suspend fun upsertDevice(device: DeviceEntity)

    @Query("UPDATE devices SET status = :status WHERE id = :id")
    suspend fun updateDeviceStatus(id: String, status: String)

    @Query("UPDATE devices SET last_seen_at = :lastSeenAt WHERE id = :id")
    suspend fun updateDeviceLastSeen(id: String, lastSeenAt: Long)

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): String?

    @Upsert
    suspend fun putSetting(setting: AppSettingEntity)
}
