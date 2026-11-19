package com.mychattingpro.core.localization

import android.content.Context
import com.example.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Formats canonical UTC server timestamps into the user's local device timezone
 * (`TimeZone.getDefault()` — never hardcoded to any single country) and selected locale.
 */
object DateTimeFormatters {

    fun formatTimeOnly(utcMillis: Long, language: AppLanguage): String {
        val sdf = SimpleDateFormat("h:mm a", language.toLocale())
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(utcMillis))
    }

    fun formatChatListTime(context: Context, utcMillis: Long, language: AppLanguage): String {
        val nowCal = Calendar.getInstance()
        val msgCal = Calendar.getInstance().apply { timeInMillis = utcMillis }
        return when {
            isSameDay(nowCal, msgCal) -> formatTimeOnly(utcMillis, language)
            isYesterday(nowCal, msgCal) -> context.getString(R.string.date_yesterday)
            nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) -> {
                val sdf = SimpleDateFormat("d MMM", language.toLocale())
                sdf.timeZone = TimeZone.getDefault()
                sdf.format(Date(utcMillis))
            }
            else -> {
                val sdf = SimpleDateFormat("d MMM yyyy", language.toLocale())
                sdf.timeZone = TimeZone.getDefault()
                sdf.format(Date(utcMillis))
            }
        }
    }

    fun formatDateSeparator(context: Context, utcMillis: Long, language: AppLanguage): String {
        val nowCal = Calendar.getInstance()
        val msgCal = Calendar.getInstance().apply { timeInMillis = utcMillis }
        return when {
            isSameDay(nowCal, msgCal) -> context.getString(R.string.date_today)
            isYesterday(nowCal, msgCal) -> context.getString(R.string.date_yesterday)
            else -> {
                val sdf = SimpleDateFormat("d MMMM yyyy", language.toLocale())
                sdf.timeZone = TimeZone.getDefault()
                sdf.format(Date(utcMillis))
            }
        }
    }

    fun formatFullDateTime(context: Context, utcMillis: Long, language: AppLanguage): String {
        val nowCal = Calendar.getInstance()
        val msgCal = Calendar.getInstance().apply { timeInMillis = utcMillis }
        val timePart = formatTimeOnly(utcMillis, language)
        return when {
            isSameDay(nowCal, msgCal) -> "${context.getString(R.string.date_today)}, $timePart"
            isYesterday(nowCal, msgCal) -> "${context.getString(R.string.date_yesterday)}, $timePart"
            else -> {
                val sdf = SimpleDateFormat("d MMM yyyy, h:mm a", language.toLocale())
                sdf.timeZone = TimeZone.getDefault()
                sdf.format(Date(utcMillis))
            }
        }
    }

    fun dayBucketKey(utcMillis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = utcMillis }
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    fun formatDuration(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun isYesterday(now: Calendar, target: Calendar): Boolean {
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return isSameDay(yesterday, target)
    }
}
