package tv.dubi.launcher

import java.util.Calendar
import java.util.TimeZone

enum class Tod { DAY, DUSK, NIGHT }

/** Wall-clock rules from HANDOFF section 3, kept free of Android types so they unit-test on the JVM. */
object Clock {
    /**
     * Every wall-clock decision (clock, date, backdrop time of day, dragon show) uses Vietnam time, not the
     * device zone: the BRAVIA sets its own zone to Australia/Sydney (auto time zone, no zone picker in Sony's
     * settings, not changeable over adb), so the device zone showed 14:38 at 10:38 in Da Nang.
     */
    val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")

    /** The wall clock in Vietnam at [millis] (UTC epoch ms). */
    fun calendar(millis: Long = System.currentTimeMillis()): Calendar = Calendar.getInstance(ZONE).apply { timeInMillis = millis }

    /** Day 06:00-17:00, dusk 17:00-19:00, night 19:00-06:00. */
    fun tod(hour: Int): Tod = when (hour) {
        in 6..16 -> Tod.DAY
        17, 18 -> Tod.DUSK
        else -> Tod.NIGHT
    }

    /** The real Dragon Bridge show: Saturday and Sunday, 21:00-21:15. */
    fun showTime(dayOfWeek: Int, hour: Int, minute: Int): Boolean =
        (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) && hour == 21 && minute < 15

    fun time(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    private val DAYS = arrayOf("Chủ nhật", "Thứ hai", "Thứ ba", "Thứ tư", "Thứ năm", "Thứ sáu", "Thứ bảy")

    /** "Chủ nhật, 25/10" */
    fun date(dayOfWeek: Int, day: Int, month1: Int): String = "${DAYS[dayOfWeek - 1]}, $day/$month1"
}
