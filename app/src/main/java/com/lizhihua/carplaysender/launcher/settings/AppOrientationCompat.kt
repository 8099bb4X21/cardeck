package com.lizhihua.carplaysender.launcher.settings

import android.content.ComponentName
import android.content.Context
import com.lizhihua.carplaysender.shizuku.Shell

/**
 * 「强制应用横屏」兼容覆盖的唯一管理口：
 *   - 用户偏好持久化在本应用 SharedPreferences（页面开关读写）；
 *   - 系统侧覆盖（am compat，持久）只在**会话期间**保持：
 *     会话建立（虚拟屏就绪）时 [applyAll] 逐应用写入，
 *     会话结束（断开/推流终止）时 [resetAll] 统一复原——
 *     避免覆盖残留影响应用在手机侧的正常竖屏使用。
 *
 * 生效组合（头条/抖音实测）：
 *   OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR + OVERRIDE_ANY_ORIENTATION
 */
object AppOrientationCompat {
    const val CHANGE_UNDEFINED_TO_NOSENSOR = 265451093
    const val CHANGE_ANY_ORIENTATION = 265464455
    private const val PREFS = "app_orientation"

    fun prefKey(component: ComponentName) = "force_${component.flattenToString()}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, component: ComponentName): Boolean =
        prefs(context).getBoolean(prefKey(component), false)

    /** 用户开关：写偏好 + 立即写系统覆盖（设置页用）。返回系统写入是否成功。 */
    fun setEnabled(context: Context, pkg: String, enable: Boolean): Boolean {
        val component = ComponentName.unflattenFromString(pkg)
        prefs(context).edit()
            .putBoolean(if (component != null) prefKey(component) else pkg, enable)
            .apply()
        val action = if (enable) "enable" else "disable"
        val ok1 = Shell.exec("am compat $action $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000).ok
        val ok2 = Shell.exec("am compat $action $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000).ok
        return ok1 && ok2
    }

    /** 会话建立：名单内应用逐个写入覆盖（幂等）。返回写入的应用数。 */
    fun applyAll(context: Context): Int {
        var count = 0
        enabledPkgs(context).forEach { pkg ->
            Shell.exec("am compat enable $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
            Shell.exec("am compat enable $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            count++
        }
        return count
    }

    /** 会话结束：名单内应用统一复原（偏好保留，仅撤销系统覆盖）。 */
    fun resetAll(context: Context): Int {
        var count = 0
        enabledPkgs(context).forEach { pkg ->
            Shell.exec("am compat disable $CHANGE_UNDEFINED_TO_NOSENSOR $pkg", timeoutMs = 4000)
            Shell.exec("am compat disable $CHANGE_ANY_ORIENTATION $pkg", timeoutMs = 4000)
            count++
        }
        return count
    }

    private fun enabledPkgs(context: Context): List<String> =
        prefs(context).all.filterValues { it == true }.keys.toList()
}
