package com.installer.toolbox.common.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 设备清单数据访问对象 */
@Dao
interface DeviceDao {

    /** 插入或覆盖（同 id 更新） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    /** 删除一条设备记录 */
    @Delete
    suspend fun delete(device: DeviceEntity)

    /** 观察全部设备，按最后在线时间倒序 */
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    /** 按主键查询 */
    @Query("SELECT * FROM devices WHERE id = :id")
    suspend fun getById(id: String): DeviceEntity?
}
