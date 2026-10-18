package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知使用权判定（纯函数）：系统把多个监听器用 `:` 拼成一个长串，
 * 必须按条目精确比对——用 `contains(包名)` 会被「前缀相同」的其它应用误判。
 */
class NotificationAccessTest {
    private val spec = "io.github.shaonialife.cardeck/io.github.shaonialife.cardeck.media.MediaListenerService"

    @Test
    fun detectsOwnComponentAmongOthers() {
        val enabled =
            "com.google.android.projection.gearhead/GearheadListenerService:" +
                "$spec:com.hihonor.health/HnNotificationListenerService"
        assertTrue(NotificationAccess.isGrantedIn(enabled, spec))
    }

    @Test
    fun detectsWhenOnlyEntry() {
        assertTrue(NotificationAccess.isGrantedIn(spec, spec))
    }

    @Test
    fun rejectsWhenAbsent() {
        assertFalse(NotificationAccess.isGrantedIn("com.other.app/OtherService", spec))
        assertFalse(NotificationAccess.isGrantedIn("", spec))
        assertFalse(NotificationAccess.isGrantedIn(null, spec))
    }

    @Test
    fun doesNotMatchByPrefixOnly() {
        // 旧包名是前缀相同的另一条授权：不能因为字符串包含就判为已授权
        // （改包名后最容易踩的坑——旧条目还在列表里，新包其实没授权）
        val staleOldPackage =
            "io.github.shaonialife.cardeck/io.github.shaonialife.cardeck.media.OtherService"
        assertFalse(NotificationAccess.isGrantedIn(staleOldPackage, spec))
    }
}
