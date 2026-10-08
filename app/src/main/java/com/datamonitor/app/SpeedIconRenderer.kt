package com.datamonitor.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale

/**
 * Малює швидкість як білу монохромну іконку для статус-бару:
 * зверху число, під ним одиниця (KB або MB, на секунду).
 */
object SpeedIconRenderer {
    private const val SIZE = 96

    // Повертає пару (число, одиниця) для іконки
    fun format(bytesPerSec: Double): Pair<String, String> {
        val kb = bytesPerSec / 1000.0
        return if (kb >= 1000.0) {
            val mb = kb / 1000.0
            val num = if (mb >= 100.0) String.format(Locale.US, "%.0f", mb)
            else String.format(Locale.US, "%.1f", mb)
            Pair(num, "MB")
        } else {
            Pair(String.format(Locale.US, "%.0f", kb), "KB")
        }
    }

    // Текстова версія для тексту сповіщення, напр. "356 КБ/с" або "1.2 МБ/с"
    fun label(bytesPerSec: Double): String {
        val (num, unit) = format(bytesPerSec)
        val ukUnit = if (unit == "MB") "МБ/с" else "КБ/с"
        return "$num $ukUnit"
    }

    fun render(bytesPerSec: Double): Bitmap {
        val (num, unit) = format(bytesPerSec)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        // Число: підбираємо розмір шрифту, щоб влізло по ширині
        var size = 64f
        paint.textSize = size
        while (paint.measureText(num) > SIZE - 4 && size > 20f) {
            size -= 2f
            paint.textSize = size
        }
        canvas.drawText(num, SIZE / 2f, 58f, paint)

        // Одиниця під числом
        paint.textSize = 34f
        canvas.drawText(unit, SIZE / 2f, 94f, paint)
        return bmp
    }
}
