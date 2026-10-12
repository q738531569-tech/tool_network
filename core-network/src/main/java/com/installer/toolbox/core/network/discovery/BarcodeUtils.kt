package com.installer.toolbox.core.network.discovery

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码/条码生成与解码（ZXing core，纯本地）。
 *
 * - [encode]：文本/链接生成二维码（QR_CODE）或条码（CODE_128 / EAN_13 / CODE_39 等）；
 * - [decode]：从 Bitmap 解码二维码/条码，返回文本与格式。
 */
object BarcodeUtils {

    /** 支持的条码格式（生成） */
    val GENERATE_FORMATS: List<BarcodeFormat> = listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.CODE_128,
        BarcodeFormat.EAN_13,
        BarcodeFormat.CODE_39
    )

    /** 生成条码/二维码 Bitmap；内容过长或不支持时返回 null */
    fun encode(
        content: String,
        format: BarcodeFormat,
        width: Int = 480,
        height: Int = 480
    ): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                // 边距调小：二维码留 1 格，条码留 0 格更紧凑
                EncodeHintType.MARGIN to if (format == BarcodeFormat.QR_CODE) 1 else 0
            )
            val matrix = MultiFormatWriter().encode(content, format, width, height, hints)
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (matrix[x, y]) {
                        0xFF000000.toInt()
                    } else {
                        0xFFFFFFFF.toInt()
                    }
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解码 Bitmap 中的二维码/条码。
     * @return 解码文本 + 条码格式；无法识别返回 null
     */
    fun decode(bitmap: Bitmap): Pair<String, BarcodeFormat>? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A,
                    BarcodeFormat.ITF,
                    BarcodeFormat.CODABAR,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.AZTEC,
                    BarcodeFormat.PDF_417
                )
            )
            val result = MultiFormatReader().apply { setHints(hints) }.decode(binary)
            result.text to result.barcodeFormat
        } catch (e: Exception) {
            null
        }
    }
}

