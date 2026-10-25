package tv.dubi.launcher

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings

enum class PanelKind { INPUT, WIFI, SETTINGS }

/**
 * A quick-panel row: [on] lights its dot green; OK starts [intent] (null rows are informational). When the
 * launcher is still in front a moment later, [fallback] opens instead: some vendor screens resolve but never
 * show for a third-party Home (Sony's sound settings on the BRAVIA 2015).
 */
class PanelRow(val text: String, val on: Boolean, val intent: Intent?, val inputId: String? = null, val fallback: Intent? = null)

class Panel(val kind: PanelKind, val title: String, val rows: List<PanelRow>)

/** Builds the HDMI / Wi-Fi / Settings quick panels from the live system state each time one opens. */
object Panels {
    private const val PREFS = "dubi"
    private const val LAST_INPUT = "lastInput"

    fun build(ctx: Context, kind: PanelKind): Panel = when (kind) {
        PanelKind.INPUT -> Panel(kind, "Nguồn vào", inputs(ctx))
        PanelKind.WIFI -> Panel(kind, "Wi-Fi", wifi(ctx))
        PanelKind.SETTINGS -> Panel(kind, "Cài đặt nhanh", settings(ctx))
    }

    fun remember(ctx: Context, row: PanelRow) {
        val id = row.inputId ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_INPUT, id).apply()
    }

    /** TV inputs (HDMI first, then AV, then the tuner), each opened through the system TV app. */
    private fun inputs(ctx: Context): List<PanelRow> {
        val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        val last = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_INPUT, null)
        val infos = (tim?.tvInputList ?: emptyList()).filter { !it.isHidden(ctx) }.sortedBy { order(it.type) }
        val rows = infos.map { info ->
            val label = info.loadCustomLabel(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: info.loadLabel(ctx).toString()
            val intent = if (info.isPassthroughInput) {
                Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
            } else {
                liveTv(ctx) ?: Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(info.id))
            }
            val tv = if (info.isPassthroughInput) liveTv(ctx)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else null
            PanelRow(label, info.id == last, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), info.id, tv)
        }.filter { resolves(ctx, it.intent!!) }
        if (rows.isNotEmpty()) return rows
        liveTv(ctx)?.let { return listOf(PanelRow("Live TV", false, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) }
        return listOf(PanelRow("Không tìm thấy nguồn vào", false, null))
    }

    private fun order(type: Int) = when (type) {
        TvInputInfo.TYPE_HDMI -> 0
        TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_SCART, TvInputInfo.TYPE_VGA, TvInputInfo.TYPE_DVI -> 1
        TvInputInfo.TYPE_TUNER -> 2
        else -> 3
    }

    /** Sony's TV app first, then the Google / AOSP Live Channels apps. */
    private fun liveTv(ctx: Context): Intent? {
        val pm = ctx.packageManager
        for (pkg in listOf("com.sony.dtv.tvx", "com.google.android.tv", "com.android.tv")) {
            pm.getLeanbackLaunchIntentForPackage(pkg)?.let { return it }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun wifi(ctx: Context): List<PanelRow> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetworkInfo
        val status = when {
            net == null || !net.isConnected -> "Chưa kết nối mạng"
            net.type == ConnectivityManager.TYPE_WIFI -> {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ssid = wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "Wi-Fi"
                "$ssid · đã kết nối"
            }
            net.type == ConnectivityManager.TYPE_ETHERNET -> "Mạng dây · đã kết nối"
            else -> "Đã kết nối mạng"
        }
        val main = mainSettings()
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).takeIf { resolves(ctx, it) } ?: main
        val fallback = main.takeIf { wifi !== main }
        return listOf(PanelRow(status, net?.isConnected == true, wifi, fallback = fallback), PanelRow("Cài đặt Wi-Fi…", false, wifi, fallback = fallback))
    }

    /**
     * The Settings button opens the main TV Settings screen directly, with no panel in between. The picture /
     * sound / date deep links all land in Sony's own settings activities, and its sound screen never opens for
     * a third-party Home, so none of them is offered. Null when nothing handles it.
     */
    fun settingsIntent(ctx: Context): Intent? = mainSettings().takeIf { resolves(ctx, it) }

    /** Only shown when the main Settings screen could not be opened, so OK never does nothing silently. */
    private fun settings(ctx: Context): List<PanelRow> = listOf(PanelRow("Không mở được Cài đặt", false, null))

    private fun mainSettings() = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun resolves(ctx: Context, intent: Intent) = intent.resolveActivity(ctx.packageManager) != null
}
