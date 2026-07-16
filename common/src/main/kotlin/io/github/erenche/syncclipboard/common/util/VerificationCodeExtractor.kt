package io.github.erenche.syncclipboard.common.util

/**
 * 短信验证码提取器 — 正则与原 React Native 端保持完全一致。
 *
 * 支持中英文常见验证码格式：
 *   • 中文关键字：验证码、动态码、授权码、校验码、代码
 *   • 英文关键字：code、verification code
 *   • 数字类验证码（4-6 位）
 *   • 字母数字混合（5-6 位）
 */
object VerificationCodeExtractor {

    /**
     * 与桌面端 SmsCodeService 一致的正则表达式。
     * group 7 = 验证码正文（whitespace 已在 extract() 中剥离）。
     */
    private val REGEX = Regex(
        "(.*)((代|授权|验证|动态|校验)码|[【\\[].*[】\\]]|[Cc][Oo][Dd][Ee]|[Vv]erification\\s?([Cc]ode)?)\\s?(G-|<#>)?([:：\\s是为]|[Ii][Ss]){0,3}[\\(（\\[【{「]?(([0-9\\s]{4,6})|([A-Za-z\\d]{5,6})(?!([Vv]erification)?([Cc][Oo][Dd][Ee])|:))[」}】\\]）\\)]?(?=([^0-9a-zA-Z]|$))(.*)"
    )

    /**
     * 从短信正文提取验证码；若无匹配返回 null。
     */
    fun extract(body: String): String? {
        val match = REGEX.find(body) ?: return null
        val code = match.groupValues.getOrNull(7) ?: return null
        if (code.isBlank()) return null
        return code.replace(Regex("\\s"), "")
    }

    /**
     * 快速判断短信正文是否含验证码（用于接收器快速过滤）。
     */
    fun contains(body: String): Boolean = REGEX.containsMatchIn(body)
}
