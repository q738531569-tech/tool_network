package com.installer.toolbox.common.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 工具箱本地数据库：设备清单 + 诊断报告。
 */
@Database(
    entities = [DeviceEntity::class, DiagReportEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ToolboxDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao

    abstract fun diagReportDao(): DiagReportDao

    companion object {
        /** 数据库文件名 */
        const val DB_NAME = "toolbox.db"

        /** 构建数据库实例（由 Application 持有单例） */
        fun create(context: Context): ToolboxDatabase =
            Room.databaseBuilder(context, ToolboxDatabase::class.java, DB_NAME).build()
    }
}
