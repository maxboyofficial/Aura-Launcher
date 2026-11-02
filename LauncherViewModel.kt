package com.aura.launcher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap,
)

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val MAX_DOCK = 5
        const val MAX_PINNED = 12
        private const val KEY_DOCK = "dock"
        private const val KEY_PINNED = "pinned"
    }

    private val ctx: Context = app
    private val launcherApps = app.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val prefs = app.getSharedPreferences("aura_launcher", Context.MODE_PRIVATE)
    private val iconPx = (58 * app.resources.displayMetrics.density).toInt()
    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _dockKeys = MutableStateFlow(readList(KEY_DOCK))
    private val _pinnedKeys = MutableStateFlow(readList(KEY_PINNED))
    val dockKeys: StateFlow<List<String>> = _dockKeys.asStateFlow()
    val pinnedKeys: StateFlow<List<String>> = _pinnedKeys.asStateFlow()

    val dock: StateFlow<List<AppEntry>> =
        combine(_apps, _dockKeys) { apps, keys -> resolve(apps, keys) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pinned: StateFlow<List<AppEntry>> =
        combine(_apps, _pinnedKeys) { apps, keys -> resolve(apps, keys) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Emits whenever the Home button is pressed while we are already on top. */
    val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var loadJob: Job? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.data?.schemeSpecificPart?.let { pkg ->
                iconCache.keys.removeIf { it.startsWith("$pkg/") }
            }
            refresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(app, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    override fun onCleared() {
        ctx.unregisterReceiver(packageReceiver)
        super.onCleared()
    }

    fun onHomePressed() {
        homePresses.tryEmit(Unit)
    }

    // ---------------------------------------------------------------- loading

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.Default) {
            val list = launcherApps.getActivityList(null, Process.myUserHandle())
                .filter { it.componentName.packageName != ctx.packageName }
                .map { info ->
                    val key = info.componentName.flattenToString()
                    val icon = iconCache.getOrPut(key) {
                        info.getBadgedIcon(0).toBitmap(iconPx, iconPx).asImageBitmap()
                    }
                    AppEntry(key, info.label.toString(), info.componentName, info.user, icon)
                }
                .sortedBy { it.label.lowercase() }
            _apps.value = list
            if (!prefs.contains(KEY_DOCK)) seedDock(list)
        }
    }

    private fun seedDock(apps: List<AppEntry>) {
        val pm = ctx.packageManager
        val probes = listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        val installed = apps.map { it.component.packageName }.toSet()
        val keys = probes.mapNotNull { probe ->
            val pkg = pm.queryIntentActivities(probe, 0)
                .map { it.activityInfo.packageName }
                .firstOrNull { it in installed }
            apps.firstOrNull { it.component.packageName == pkg }?.key
        }.distinct()
        saveList(KEY_DOCK, keys)
        _dockKeys.value = keys
    }

    private fun resolve(apps: List<AppEntry>, keys: List<String>): List<AppEntry> {
        val byKey = apps.associateBy { it.key }
        return keys.mapNotNull { byKey[it] }
    }

    // ------------------------------------------------------------ persistence

    private fun readList(name: String): List<String> =
        prefs.getString(name, "").orEmpty().split("|").filter { it.isNotBlank() }

    private fun saveList(name: String, list: List<String>) {
        prefs.edit().putString(name, list.joinToString("|")).apply()
    }

    /** @return false if the dock is full. */
    fun toggleDock(key: String): Boolean {
        val cur = _dockKeys.value
        val next = when {
            key in cur -> cur - key
            cur.size >= MAX_DOCK -> return false
            else -> cur + key
        }
        _dockKeys.value = next
        saveList(KEY_DOCK, next)
        return true
    }

    /** @return false if the home screen is full. */
    fun togglePinned(key: String): Boolean {
        val cur = _pinnedKeys.value
        val next = when {
            key in cur -> cur - key
            cur.size >= MAX_PINNED -> return false
            else -> cur + key
        }
        _pinnedKeys.value = next
        saveList(KEY_PINNED, next)
        return true
    }

    // ---------------------------------------------------------------- actions

    fun launch(app: AppEntry) {
        runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) }
    }

    fun showInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    fun uninstall(app: AppEntry) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.component.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
