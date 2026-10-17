package com.lizhihua.carplaysender.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 默认地图解析回归测试。
 *
 * 重点是「未设置默认地图」这条路径：旧实现里 `resolve()` 在该分支回调
 * `VirtualLauncher.defaultMapComponent()`、后者又转调回 `resolve()`，构成无限递归
 * → StackOverflowError。症状是卸载重装后地图卡不渲染（偏好被清空才触发），而
 * 用户在设置里重选一次即恢复——很容易被误判成虚拟屏/渲染问题（2026-10-18 实测）。
 *
 * 这里用包名字符串代替 ComponentName：`resolveDefaultMap` 是泛型化的纯函数，
 * 单测环境里 Android 类（ComponentName）的方法都是未实现的空桩。
 */
class DefaultMapPrefsTest {
    private val priority = listOf("car.map", "phone.map", "baidu.map")

    /** 用字符串做「已安装应用 → 可启动组件」的替身。 */
    private fun pick(
        manual: String?,
        installed: Set<String>,
    ): String? =
        resolveDefaultMap(
            manual = manual,
            priority = priority,
            componentFor = { pkg -> if (pkg in installed) pkg else null },
        )

    /** 未设置默认地图：按优先序取第一个「已安装」的（旧实现此处会栈溢出）。 */
    @Test
    fun unsetFallsBackToPriorityOrder() {
        assertEquals("phone.map", pick(manual = null, installed = setOf("phone.map", "baidu.map")))
    }

    /** 显式设置优先于优先序。 */
    @Test
    fun manualChoiceWins() {
        assertEquals("baidu.map", pick(manual = "baidu.map", installed = setOf("car.map", "baidu.map")))
    }

    /** 显式设置的应用已卸载：回落到优先序，而不是返回 null/崩溃。 */
    @Test
    fun uninstalledManualFallsBack() {
        assertEquals("car.map", pick(manual = "gone.map", installed = setOf("car.map")))
    }

    /** 一台地图都没有：返回 null（调用方显示「地图 tile 空」提示）。 */
    @Test
    fun noneInstalledReturnsNull() {
        assertNull(pick(manual = null, installed = emptySet()))
    }

    /** 反复解析必须每次都能终止（旧实现会 StackOverflowError）。 */
    @Test
    fun resolveTerminatesWithoutRecursion() {
        repeat(100) { assertNull(pick(manual = null, installed = emptySet())) }
    }
}
