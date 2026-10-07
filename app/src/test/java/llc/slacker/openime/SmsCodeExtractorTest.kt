package llc.slacker.openime

import llc.slacker.openime.keyboard.SmsCodeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsCodeExtractorTest {
    @Test
    fun findsTheCodeNextToTheKeyword() {
        assertEquals("482913", SmsCodeExtractor.extract("【某某】您的验证码是482913，5分钟内有效。"))
        assertEquals("1234", SmsCodeExtractor.extract("验证码：1234，请勿泄露"))
        assertEquals("667788", SmsCodeExtractor.extract("Your verification code is 667788."))
    }

    @Test
    fun theCodeNearestTheKeywordWins() {
        assertEquals("8802", SmsCodeExtractor.extract("订单 20261007123 已发货，验证码 8802"))
    }

    @Test
    fun messagesWithoutAKeywordOrCodeGiveNothing() {
        assertNull(SmsCodeExtractor.extract("您的快递 123456 已到达，请取件"))
        assertNull(SmsCodeExtractor.extract("验证码已发送，请查收"))
        assertNull(SmsCodeExtractor.extract("验证码 123"))
    }
}
