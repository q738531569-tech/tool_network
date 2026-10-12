package com.installer.toolbox.common.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 诊断报告数据访问对象 */
@Dao
interface DiagReportDao {

    /** 新增一条报告 */
    @Insert
    suspend fun insert(report: DiagReportEntity)

    /** 观察全部报告，按时间倒序 */
    @Query("SELECT * FROM diag_reports ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DiagReportEntity>>

    /** 清空全部报告（预留功能） */
    @Query("DELETE FROM diag_reports")
    suspend fun clear()
}
