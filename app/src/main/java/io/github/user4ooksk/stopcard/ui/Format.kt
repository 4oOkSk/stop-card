package io.github.user4ooksk.stopcard.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.user4ooksk.stopcard.R
import io.github.user4ooksk.stopcard.model.StopReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Calendar
import java.util.Date

/** "0:42" style countdown. */
fun countdownLabel(millis: Long): String {
    val total = ((millis + 999) / 1_000).coerceAtLeast(0)
    return if (total >= 3_600) {
        "%d:%02d:%02d".format(total / 3_600, total % 3_600 / 60, total % 60)
    } else {
        "%d:%02d".format(total / 60, total % 60)
    }
}

@Composable
fun reasonLabel(reason: StopReason): String = when (reason) {
    StopReason.CARD_REMOVED -> "最后一张卡移除"
    StopReason.LAST_VISIBLE -> "退出使用后停止"
    StopReason.MANUAL -> "手动停止"
    StopReason.FLOW_FINISHED -> "操作完成后停止"
    StopReason.UNLICENSED_RUNNING -> "无许可后台运行"
    StopReason.CLOSED_APP_RESTARTED -> "已停止应用后台重启"
    StopReason.OBSERVE -> "观察：本来会停止"
    StopReason.ERROR -> "状态或执行错误"
    StopReason.REVIEW -> "等待复核"
    StopReason.BLOCKED -> "已拦截或未执行"
    StopReason.CANCELED -> "复核取消"
}

@Composable
fun relativeTime(time: Long, now: Long): String {
    val elapsed = now - time
    return when {
        elapsed < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.time_just_now)
        elapsed < DateUtils.HOUR_IN_MILLIS -> stringResource(R.string.time_minutes_ago, (elapsed / DateUtils.MINUTE_IN_MILLIS).toInt())
        elapsed < 6 * DateUtils.HOUR_IN_MILLIS -> stringResource(R.string.time_hours_ago, (elapsed / DateUtils.HOUR_IN_MILLIS).toInt())
        else -> dayAndTime(time, now)
    }
}

@Composable
fun timeOfDay(time: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(time))

@Composable
fun dayLabel(dayStart: Long, now: Long): String {
    val today = startOfDay(now)
    return when (dayStart) {
        today -> stringResource(R.string.time_today)
        startOfDay(today - 1) -> stringResource(R.string.time_yesterday)
        else -> DateUtils.formatDateTime(
            LocalContext.current, dayStart,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL,
        )
    }
}

@Composable
private fun dayAndTime(time: Long, now: Long): String {
    val day = dayLabel(startOfDay(time), now)
    return "$day, ${timeOfDay(time)}"
}

/** Local midnight of the day [time] falls in. */
fun startOfDay(time: Long): Long = Calendar.getInstance().run {
    timeInMillis = time
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

/** Local midnight that starts the day after [time]. */
fun nextDayStart(time: Long): Long = daysBefore(time, -1)

/** Emits the start of today, and again whenever a new day begins. */
fun dayStarts(): Flow<Long> = flow {
    while (true) {
        val now = System.currentTimeMillis()
        emit(startOfDay(now))
        delay(nextDayStart(now) - now + 1_000)
    }
}

/** Local midnight [days] days before the day of [time]. */
fun daysBefore(time: Long, days: Int): Long = Calendar.getInstance().run {
    timeInMillis = startOfDay(time)
    add(Calendar.DAY_OF_YEAR, -days)
    timeInMillis
}
