package com.installer.toolbox.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.installer.toolbox.core.agent.ReportWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「导出报告」按钮：把结果文本写入 Download/装维通 目录（ReportWriter 统一实现）。
 * Android 9- 设备先申请 WRITE_EXTERNAL_STORAGE 运行时权限；结果用 Toast 提示。
 */
@Composable
fun ExportReportButton(
    fileName: String,
    text: String,
    modifier: Modifier = Modifier,
    buttonText: String = "导出报告"
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            doExport(context, scope, fileName, text)
        } else {
            Toast.makeText(context, "需要存储权限才能导出报告", Toast.LENGTH_LONG).show()
        }
    }
    Button(
        onClick = {
            // Android 9- 需要 WRITE_EXTERNAL_STORAGE 运行时权限（Android 10+ 用 MediaStore 免权限）
            if (Build.VERSION.SDK_INT < 29 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                doExport(context, scope, fileName, text)
            }
        },
        modifier = modifier
    ) {
        Icon(Icons.Filled.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(buttonText)
    }
}

/** 后台写文件（ReportWriter 内部已切 IO 线程），Toast 提示结果 */
private fun doExport(context: Context, scope: CoroutineScope, fileName: String, text: String) {
    scope.launch {
        val path = ReportWriter.writeReport(context, fileName, text)
        Toast.makeText(
            context,
            if (path.startsWith("下载/装维通/")) "已导出：$path" else path,
            Toast.LENGTH_LONG
        ).show()
    }
}
