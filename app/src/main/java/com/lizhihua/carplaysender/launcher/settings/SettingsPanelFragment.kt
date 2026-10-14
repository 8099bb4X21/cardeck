package com.lizhihua.carplaysender.launcher.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.lizhihua.carplaysender.launcher.VirtualLauncher

/** 设置面板宿主：页内轻提示（车机=页内 Toast 视图；手机=系统 Toast）。 */
interface SettingsToastHost {
    fun showToast(text: String)
}

/**
 * 设置内容面板基类：共享应用清单（缓存直读 + 冷路径异步扫描）与宿主轻提示。
 * 应用清单进程级共享（VirtualLauncher 缓存），桌面扫描后各面板秒开。
 */
abstract class SettingsPanelFragment : Fragment() {
    var apps: List<VirtualLauncher.AppEntry> = emptyList()
        private set

    /** 清单就绪回调（主线程；缓存命中时同步回调）。 */
    protected fun loadApps(onReady: () -> Unit) {
        val appContext = requireContext().applicationContext
        VirtualLauncher.peekCachedApps()?.takeIf { it.isNotEmpty() }?.let {
            apps = it
            onReady()
            return
        }
        Thread({
            val scanned = VirtualLauncher.launchableApps(appContext)
            VirtualLauncher.adoptAppsCache(scanned)
            requireActivity().runOnUiThread {
                if (!isAdded || isDetached) return@runOnUiThread
                apps = scanned
                onReady()
            }
        }, "settings-panel-scan").start()
    }

    protected fun hostToast(text: String) {
        val host = activity
        if (host is SettingsToastHost) {
            host.showToast(text)
        } else {
            android.widget.Toast
                .makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        onViewCreated(view)
    }

    /** onViewCreated 简化版（本页无状态恢复需求）。 */
    protected abstract fun onViewCreated(view: View)
}
