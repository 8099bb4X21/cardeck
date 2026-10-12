package com.lizhihua.carplaysender.launcher

import android.content.Context
import android.content.ComponentName

/**
 * 默认地图偏好（仪表盘 tile 与地图屏启动用）。
 *
 * 白名单 [VirtualLauncher.MAP_PACKAGES_PRIORITY] 内单选；未设置时按优先序取
 * 第一个已安装者。持久化 key 为包名。
 */
object DefaultMapPrefs {

    private const val PREFS_NAME = "settings"
    private const val KEY_DEFAULT_MAP = "defaultMapPackage"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户显式设置的默认地图包名（null = 未设置，走优先序）。 */
    fun manualPackage(context: Context): String? =
        prefs(context).getString(KEY_DEFAULT_MAP, null)

    /** 当前生效的默认地图（显式设置优先；否则按优先序取第一个已安装的）。 */
    fun resolve(context: Context): ComponentName? {
        manualPackage(context)?.let { pkg ->
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            val activity = launch?.let {
                context.packageManager.resolveActivity(it, 0)?.activityInfo
            }
            if (activity != null) return ComponentName(pkg, activity.name)
            // 显式设置的应用已卸载：回落优先序
        }
        return VirtualLauncher.defaultMapComponent(context)
    }

    fun set(context: Context, packageName: String?) {
        if (packageName == null) {
            prefs(context).edit().remove(KEY_DEFAULT_MAP).apply()
        } else {
            prefs(context).edit().putString(KEY_DEFAULT_MAP, packageName).apply()
        }
    }
}
