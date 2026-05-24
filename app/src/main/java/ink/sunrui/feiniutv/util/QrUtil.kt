package ink.sunrui.feiniutv.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 用 ZXing 把任意字符串编码成 QR Bitmap。
 *
 * 设计：
 *   - 纠错等级 M（中等）— 适合短 URL，体积/扫码速度平衡
 *   - 黑模块用品牌色背景反色（深底白图），与 lite 整体深色风格一致
 *   - 边距 margin=1（quietZone）— 默认 4 太空，1 在 TV 上视觉更紧凑
 */
object QrUtil {

    /**
     * @param content QR 编码内容（URL 或文本）
     * @param size 目标边长（像素）
     * @param fg 前景色（模块色）；默认白
     * @param bg 背景色；默认透明
     */
    fun encode(
        content: String,
        size: Int,
        fg: Int = Color.WHITE,
        bg: Int = Color.TRANSPARENT
    ): Bitmap? {
        if (content.isBlank() || size <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) fg else bg
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: Exception) {
            null
        }
    }
}
