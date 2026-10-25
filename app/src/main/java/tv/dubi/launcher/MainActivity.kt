package tv.dubi.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent

/**
 * The only Activity: the HOME/LEANBACK launcher. Plain framework Activity (no AndroidX, no Compose) to keep
 * startup and RAM low on a 1.6 GB TV. No services: installs and updates are picked up by a receiver that
 * lives only as long as this Activity, and reconciled against the tile cache on every start.
 */
class MainActivity : Activity(), LauncherView.Host {
    private lateinit var apps: Apps
    private lateinit var view: LauncherView
    /** Boot plays once per cold start of the launcher, not when it comes back from an app. */
    private var fresh = true

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            apps.reload()
            view.setApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this) { if (::view.isInitialized) view.setApps() }
        apps.reload()
        view = LauncherView(this, apps, this)
        setContentView(view)
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
    }

    override fun onResume() {
        super.onResume()
        view.onResumed(fresh)
        fresh = false
    }

    override fun onPause() {
        view.onPaused()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packages)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) view.onHomeIntent()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = view.handleKey(event) || super.dispatchKeyEvent(event)

    override fun launch(app: AppEntry): Boolean = open(app.intent())

    override fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("Dubi", "cannot open $intent", e)
        false
    } catch (e: SecurityException) {
        Log.w("Dubi", "cannot open $intent", e)
        false
    }
}
