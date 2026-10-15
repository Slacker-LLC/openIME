package llc.slacker.openime

import androidx.test.ext.junit.runners.AndroidJUnit4
import llc.slacker.openime.core.PinyinLocation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 应用内设置页改这一项时，在场的输入法要收到通知：显示位置由服务决定写不写输入框，
 * 不像双拼方案那样每次同步自读，只有键盘自己知道是不够的。
 */
@RunWith(AndroidJUnit4::class)
class PinyinLocationWiringInstrumentedTest {

    @Test
    fun theSettingsPageAnswersTheChange() {
        val method = ImeSettingsActivity::class.java.getDeclaredMethod(
            "onPinyinLocationChanged",
            PinyinLocation::class.java,
        )
        assertEquals(
            "设置页要自己接这个回调，而不是用接口里的空实现",
            ImeSettingsActivity::class.java,
            method.declaringClass,
        )
    }
}
