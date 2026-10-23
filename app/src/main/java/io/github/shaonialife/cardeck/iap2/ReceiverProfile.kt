package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.net.AirPlayFeatures

/**
 * **车机画像**：把散落的版本/能力信号收口成一份可解释的判定。
 *
 * 信号来自三处、到达时机不同：0x1D01 身份帧（蓝牙 bring-up 早期）、mDNS/`/info` 的
 * features 与 srcvers（端点解析期）、实车行为（挑战长度/拆链特征，走既有 Memory 学习）。
 * 每次有新信号到达都可重算——[classify] 是纯函数，同一输入恒同一输出。
 *
 * 两条铁律（HID 描述符教训的推广）：
 *  1. **按能力分支，不按版本号/品牌硬编码**——srcvers/features 只做先验提示，
 *     真正分派以能力清单（收/发清单、audioFormats、hidDescriptors）与实测行为为准；
 *  2. **判定必须可解释**——[describe] 把依据写进日志，判错时一眼能看出是哪条信号误导。
 */
object ReceiverProfile {
    /** 三时代（对照 catplay 分类，§9f）。UNKNOWN = 信号缺失，保持旧路径不激进。 */
    enum class Era { PRE_HOMEKIT, OPTIONAL_PAIRING, MODERN, UNKNOWN }

    /** WiFi 凭据交换协议代际（PR #10 的分派依据，收口进画像）。 */
    enum class WifiGen { LEGACY, NEW, UNKNOWN }

    /**
     * iAP2 虚拟链路代际（2026-10-23 分代重构的判定，纯函数）。
     *
     * 三代车机对「detect 之后事件通道上的 marker」响应策略不同，必须显式分派：
     *  - [LinkGeneration.CLASSIC_MIB3]（奥迪 DELPHI 真车）：marker 是周期探测，
     *    **忽略**——响应它会启动 MIB3 隧道协商，车机随即订阅「仪表盘数据」通道并
     *    等待填充，CarPlay UI 启动被挂起：推流中但停在原车界面、要重启车机
     *    （0.1.13/0.1.14 双车复现）。0.1.9 忽略 marker 时 CarPlay 照常进入；
     *    真 iPhone sysdiagnose 的 iAPChannel=0 证明真 iPhone 不建隧道。
     *  - [LinkGeneration.EVENT_LINK]（Honda HFT 等）：marker 是「等会话表建链」的
     *    硬需求（1Hz 重发 160 次量级），按阈值升级协商——媒体键（0x6802）的载体。
     *
     * 判据（全部来自协议自报，零品牌猜测之外的新依赖）：
     * DELPHI 制造 + AUDI 型号 + 稀疏清单（合计 <10 项，实测 2+5）→ CLASSIC_MIB3。
     * 三条同时命中才算——单条都可能误伤（清单稀疏的其它车机、非奥迪的 DELPHI 件）。
     * 其余一切（含未知车机）→ EVENT_LINK：与 0.1.13 前行为兼容，协商轮次有上限
     * （5 轮后静默），不可恢复的挂死风险只存在于 CLASSIC_MIB3 被误判时。
     */
    enum class LinkGeneration { CLASSIC_MIB3, EVENT_LINK }

    /**
     * 从 0x1D01 自报信号判定链路代际（见 [LinkGeneration] 文档）。
     * 信号不足（清单未提供/身份缺失）→ EVENT_LINK（0.1.13 前兼容行为）。
     */
    fun linkGenerationOf(signals: Signals): LinkGeneration {
        val manufacturer = signals.manufacturer?.lowercase().orEmpty()
        val model = signals.model?.lowercase().orEmpty()
        val listTotal = (signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0)
        val listsSparse = signals.sendIds != null && signals.receiveIds != null && listTotal in 1..9
        val isDelphiAudi = manufacturer.contains("delphi") && model.contains("audi")
        return if (isDelphiAudi && listsSparse) {
            LinkGeneration.CLASSIC_MIB3
        } else {
            LinkGeneration.EVENT_LINK
        }
    }

    /** 画像输入：全字段可空——信号到多少算多少。 */
    data class Signals(
        val name: String? = null,
        val model: String? = null,
        val manufacturer: String? = null,
        /** AirPlay features 位图（mDNS TXT / /info）；null = 未取到。 */
        val features: Long? = null,
        /** AirPlay 栈版本（mDNS TXT srcvers / 0x4301 sourceVersion）。 */
        val srcvers: String? = null,
        /** 0x1D01 参数 6：车机声明「会发送」的消息号清单；null = 未提供。 */
        val sendIds: Set<Int>? = null,
        /** 0x1D01 参数 7：车机声明「可接收」的消息号清单；null = 未提供。 */
        val receiveIds: Set<Int>? = null,
    )

    data class Profile(
        val era: Era,
        val wifiGen: WifiGen,
        /** 身份键（与 MfiLengthMemory/AudioProfileMemory 同源，后续 Memory 并表用）。 */
        val identityKey: String,
        /** 判定依据（可解释性：每条结论对应的信号原文）。 */
        val reasons: List<String>,
    )

    fun classify(signals: Signals): Profile {
        val reasons = ArrayList<String>()
        val pairing = AirPlayFeatures.supportsPairing(signals.features)
        val era =
            when {
                pairing == null -> Era.UNKNOWN
                !pairing -> {
                    reasons += "features(${signals.features?.let { "0x${it.toString(16)}" } ?: "?"}) 无 bit38=HK_PAIRING_AND_ENCRYPT"
                    Era.PRE_HOMEKIT
                }

                else -> {
                    reasons += "features bit38=HK_PAIRING_AND_ENCRYPT 已声明"
                    Era.MODERN
                }
            }
        if (signals.srcvers != null) reasons += "srcvers=${signals.srcvers}"
        if (era != Era.PRE_HOMEKIT && signals.srcvers != null) {
            // 先验提示（不改变判定）：老版本号 + 未声明配对 → v280 可选配对档
            val v = signals.srcvers.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0
            if (v in 1..36599 && era == Era.UNKNOWN) reasons += "srcvers 提示 v280 可选配对档（仅先验）"
        }

        val wifiGen =
            when {
                signals.sendIds == null && signals.receiveIds == null -> {
                    reasons += "0x1D01 未提供收/发清单"
                    WifiGen.UNKNOWN
                }

                signals.sendIds?.contains(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) == true ||
                    signals.receiveIds?.contains(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION) == true -> {
                    reasons += "清单声明 0x5703/0x5702（旧协议在场）"
                    WifiGen.LEGACY
                }

                (signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0) >= 10 -> {
                    // 清单齐且相当完整仍无 0x5703/0x5702——BMW iD9 形态（两清单均 10+ 项）。
                    // 不能仅凭「未声明」判新：奥迪清单只列 CarPlay 生命周期消息（实测
                    // 发送清单 2 项/可收 5 项，均无 0x57xx），但旧协议工作正常——
                    // 稀疏清单不算数，保守 UNKNOWN 走旧协议（0x5703 有超时兜底）
                    reasons += "清单完整（${(signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0)} 项）仍无 0x5703/0x5702——AP 信息由 0x4301 提供"
                    WifiGen.NEW
                }

                (signals.sendIds != null || signals.receiveIds != null) -> {
                    reasons += "清单稀疏且未声明 0x5703/0x5702——不构成「新协议」证据，保守按旧协议（超时兜底）"
                    WifiGen.UNKNOWN
                }

                else -> WifiGen.UNKNOWN
            }
        return Profile(era, wifiGen, identityKeyOf(signals), reasons)
    }

    /** 身份原文（name+model+manufacturer，与 0x1D01 逐字一致；空档跳过）。 */
    fun identityKeyOf(signals: Signals): String =
        listOfNotNull(
            signals.name?.takeIf { it.isNotBlank() },
            signals.model?.takeIf { it.isNotBlank() },
            signals.manufacturer?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { "unknown" }

    /** 一行可读判定（进文件日志）。 */
    fun describe(profile: Profile): String =
        "PROFILE: era=${profile.era} wifiGen=${profile.wifiGen} key=${MfiLengthMemory.receiverKey(profile.identityKey)}" +
            " 依据[" + profile.reasons.joinToString("；") + "]"
}
