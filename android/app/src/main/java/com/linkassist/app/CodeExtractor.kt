package com.linkassist.app

/** 从短信/通知文本里提取验证码 */
object CodeExtractor {

    // 关键词后面紧跟的验证码(纯数字或字母数字混合)
    private val withKeyword = Regex(
        """(?:验证码|校验码|动态码|确认码|动态密码|verification\s*code|verify\s*code|security\s*code|access\s*code|code|otp|pin|password)[^0-9A-Za-z]{0,12}([0-9A-Za-z]{4,8})""",
        RegexOption.IGNORE_CASE,
    )

    // 兜底:找最长的 4~8 位连续数字
    private val digits = Regex("""(?<![0-9])[0-9]{4,8}(?![0-9])""")

    fun extract(text: String): String? {
        if (text.isBlank()) return null
        withKeyword.find(text)?.groupValues?.get(1)?.let { return it }
        return digits.findAll(text).maxByOrNull { it.value.length }?.value
    }
}
