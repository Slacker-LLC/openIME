package llc.slacker.openime

import llc.slacker.openime.keyboard.SmsCodeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsCodeExtractorTest {
    @Test
    fun findsCodesDirectlyAttachedToChineseOrEnglishLabels() {
        assertCodes(
            "482913" to "【某某】您的验证码是482913，5分钟内有效。",
            "1234" to "验证码：1234，请勿泄露",
            "667788" to "Your verification code is 667788.",
            "123456" to "动态密码为123456",
            "123456" to "Your one-time password is 123456.",
            "123456" to "OTP: 123456",
            "123456" to "Your security code is [123456].",
            "123456" to "驗證碼為123456",
            "123456" to "验证码为您123456",
            "123456" to "验证码为您的123456",
            "123456" to "登入碼：123456",
            "123456" to "Your sign-in code: 123456",
            "12345678" to "验证代码 12345678",
            "000012" to "Your passcode is 000012",
        )
    }

    @Test
    fun aCodeBeforeTheKeywordKeepsItsFullValue() {
        assertCodes(
            "1234" to "1234是验证码，订单号 56789012",
            "667788" to "667788 is your verification code. Do not share it.",
            "AB12cd" to "AB12cd is your login code.",
            "123456" to "Use 123456 as your code.",
            "123456" to "【123456】是您的验证码",
        )
    }

    @Test
    fun groupsAndFullWidthCharactersAreNormalizedButLetterCaseIsPreserved() {
        assertCodes(
            "123456" to "验证码：123 456，请勿泄露",
            "123456" to "Your verification code is 123-456.",
            "123456" to "OTP: 1 2 3 4 5 6",
            "123456" to "OTP: 12-34-56",
            "123456" to "验证码：123 456",
            "123456" to "验证码：123 456",
            "123456" to "验证码：１２３４５６",
            "Ab12Cd" to "验证码：Ａｂ１２Ｃｄ",
            "aB12cd" to "Your verification code is aB12cd.",
            "1234AB" to "Your code is 1234AB",
            "AB1234" to "Your code: AB1234",
            "ABCDEF" to "Your verification code is ABCDEF.",
            "ABCD" to "验证码【ABCD】",
        )
    }

    @Test
    fun unrelatedNumbersCannotWinOverTheExplicitCode() {
        assertCodes(
            "8802" to "订单 20261007123 已发货，验证码 8802",
            "Ab12Cd" to "验证码：Ab12Cd，客服电话4001234567，金额1234.56元",
            "123456" to "Your order 56789012: verification code 123456. Valid for 10 minutes.",
            "123456" to "Call 020-12345678 if needed. Your code is 123456.",
            "123456" to "验证码 123456，订单号 56789012",
        )
    }

    @Test
    fun phoneNumbersOrdersAmountsDatesAndUrlsAreNotCodes() {
        assertNoCodes(
            "验证码已发送至手机13800138000",
            "验证码已发送，客服电话12345678",
            "验证码发送失败，请拨打400-123-4567",
            "验证码已发送至 138 0013 8000",
            "验证码已发送至 +86 13800138000",
            "验证码已发送。订单号 12345678",
            "验证码：订单号12345678",
            "验证码：1234.56元",
            "验证码有效期至20261009",
            "验证码：20261009",
            "验证码：2026年10月09日",
            "验证码 1234元",
            "code: 1234 USD",
            "Your verification code expires in 1234 minutes",
            "Verification failed. Visit https://example.com/123456",
            "验证码：https://example.com/123456",
            "验证码：user123456@example.com",
            "Your order code is 12345678",
            "Your tracking code: 12345678",
            "Promo code: SAVE1234",
            "Discount code: SAVE1234",
            "Your postal code is 12345",
            "Error code: 123456",
        )
    }

    @Test
    fun wholeTokensAreValidatedWithoutExtractingNumericFragments() {
        assertNoCodes(
            "验证码 123",
            "验证码 123456789",
            "验证码 ABC123456XYZ",
            "验证码 ab123456789cd",
            "验证码 1234_5678",
            "验证码 1234/5678",
            "验证码 12345-67890",
            "验证码 123 4567",
            "验证码 1234-ABCD",
            "验证码 1234.5678",
            "验证码 +123456",
            "验证码 @123456",
        )
    }

    @Test
    fun ordinaryEnglishAndNonVerificationMessagesDoNotCreateCodes() {
        assertNoCodes(
            "您的快递 123456 已到达，请取件",
            "验证码已发送，请查收",
            "hotpot 12345 ready",
            "Please verify order 12345678",
            "Verification successful. Order 12345678 is ready.",
            "Your code is valid",
            "Your code is VALID",
            "Your code is EXPIRED",
            "code: PLEASE enter your account number 123456",
            "Your code is please",
            "Your code is signin",
            "Your code is abcdef",
            "barcode 123456",
        )
    }

    @Test
    fun distinctExplicitCodesAreAmbiguousAndRepeatedCodesAreSafe() {
        assertNull(SmsCodeExtractor.extract("验证码123456，另一个验证码654321"))
        assertNull(SmsCodeExtractor.extract("Your old code is 123456; your new code is 654321."))
        assertCodes(
            "123456" to "验证码123456。Your verification code is 123456.",
            "123456" to "123456 is your verification code. 验证码：123456",
        )
    }

    private fun assertCodes(vararg cases: Pair<String, String>) {
        cases.forEach { (expected, body) -> assertEquals(body, expected, SmsCodeExtractor.extract(body)) }
    }

    private fun assertNoCodes(vararg bodies: String) {
        bodies.forEach { body -> assertNull(body, SmsCodeExtractor.extract(body)) }
    }
}
