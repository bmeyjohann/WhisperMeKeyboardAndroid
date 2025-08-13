package dev.patrickgold.florisboard.app.settings.voiceinput

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object AppListCache {
    private const val PREFS_NAME = "app_list_cache"
    private const val KEY_APP_LIST = "apps_json"

    data class AppInfo(val appName: String, val packageName: String)

    fun fetchInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.filter {
            pm.getLaunchIntentForPackage(it.packageName) != null
        }.map {
            AppInfo(
                appName = pm.getApplicationLabel(it).toString(),
                packageName = it.packageName
            )
        }.sortedBy { it.appName.lowercase() }
    }

    fun saveAppList(context: Context, appList: List<AppInfo>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        appList.forEach {
            val obj = JSONObject()
            obj.put("appName", it.appName)
            obj.put("packageName", it.packageName)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_APP_LIST, arr.toString()).apply()
    }

    fun loadAppList(context: Context): List<AppInfo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_APP_LIST, null) ?: return emptyList()
        val arr = JSONArray(json)
        val result = mutableListOf<AppInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(AppInfo(obj.getString("appName"), obj.getString("packageName")))
        }
        return result
    }

    suspend fun refreshAppListAsync(context: Context) {
        val apps = fetchInstalledApps(context)
        saveAppList(context, apps)
    }
} 