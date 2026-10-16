package com.lizhihua.carplaysender.launcher.settings

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

    /** 偏好键统一按「包名」存取（am compat 也以包名为对象；读取侧统一 removePrefix 兼容旧键）。 */
    private fun key(pkg: String) = "force_$pkg"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(
        context: Context,
        pkg: String,
    ): Boolean = prefs(context).getBoolean(key(pkg), false)

    /** 用户开关：写偏好 + 立即写系统覆盖（设置页用）。返回系统写入是否成功。 */
    fun setEnabled(
        context: Context,
        pkg: String,
        enable: Boolean,
    ): Boolean {
        prefs(context).edit().putBoolean(key(pkg), enable).apply()
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

    /**
     * 系统侧当前真正开着覆盖的「覆盖键」集合（事实源；键可能是包名，也可能是
     * 历史脏数据里的 `包/组件` 串）。
     *
     * 偏好文件读不到「系统实际状态」：**卸载会删掉偏好，系统侧 am compat 覆盖却会留下**
     * （2026-10-16 实测：设备上有飞书/大众点评/荣耀云音乐三个残留）。查询只能走
     * `dumpsys platform_compat`（该 ROM 无 `cmd platform_compat`），实测全量 30ms/36KB，
     * 过滤到关心的两个 ChangeId 后约 3KB——**面板打开时查一次**，行绑定只做内存查表，
     * 绝不可每行一次（40+ 应用 × ~100ms = 卡顿）。
     *
     * **必须同时解析 packageOverrides 与 rawOverrides**：已卸载应用的覆盖只存在于
     * rawOverrides——packageOverrides 是平台按「已安装包」解析后的映射，包不存在时条目
     * 消失（2026-10-16 实测：给未安装包写的覆盖只在 rawOverrides 可见）。「其它系统
     * 残留」汇总正是为卸载残留设计的，漏读会让横幅永远空着。
     *
     * 两个 ChangeId 取或：只开其一的半残状态同样视为「已开启」（清理时会一并写两个）。
     */
    fun systemEnabled(): Set<String> {
        val out =
            Shell
                .exec(
                    "dumpsys platform_compat | grep -E 'ChangeId\\(${CHANGE_UNDEFINED_TO_NOSENSOR};|ChangeId\\(${CHANGE_ANY_ORIENTATION};'",
                    timeoutMs = 6_000L,
                ).output
        if (out.isBlank()) return emptySet()
        return out
            .lineSequence()
            .flatMap { line ->
                // packageOverrides={com.a=true, ...}；rawOverrides 里还可能有 包/组件 键
                Regex("(?:packageOverrides|rawOverrides)=\\{([^}]*)\\}")
                    .findAll(line)
                    .flatMap { m ->
                        // 键容许多数可打印字符（含 / 的组件键）；只取 =true
                        Regex("([^=,{}\\s]+)=true").findAll(m.groupValues[1]).map { it.groupValues[1] }
                    }
            }.toSet()
    }

    /**
     * 清除单个「覆盖键」的系统侧覆盖（两个 ChangeId 都写 disable）。
     * 仅动系统覆盖、**不动偏好**——用于清理「偏好已不在（卸载残留）但覆盖还开着」的条目；
     * 名单内的应用请用 [setEnabled]（会同步偏好，避免 UI 与系统状态脱节）。
     * 注意：am compat disable 会立即杀掉目标应用进程（新设置需重启应用生效）。
     */
    fun clearSystemOnly(key: String): Boolean {
        val ok1 = Shell.exec("am compat disable $CHANGE_UNDEFINED_TO_NOSENSOR $key", timeoutMs = 4000).ok
        val ok2 = Shell.exec("am compat disable $CHANGE_ANY_ORIENTATION $key", timeoutMs = 4000).ok
        return ok1 && ok2
    }

    private fun enabledPkgs(context: Context): List<String> =
        prefs(context)
            .all
            .filterValues { it == true }
            .keys
            .map { it.removePrefix("force_") }
}
