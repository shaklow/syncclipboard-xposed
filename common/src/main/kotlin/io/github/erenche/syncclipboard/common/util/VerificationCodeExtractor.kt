package io.github.erenche.syncclipboard.common.util

/**
 * 短信验证码提取器。
 *
 * 支持中文/英文验证码提示词、全角标点、括号、4-8 位数字验证码和 5-8 位字母数字验证码。
 * 同时提供跨短信/通知接收器的共享去重闸门。
 */
object VerificationCodeExtractor {

    /** 常见验证码提示词；先用轻量关键词过滤，再提取紧随提示词的数字。 */
    private val KEYWORD_REGEX = Regex(
        "(?:验证码|动态码|授权码|校验码|验证代码|验证码是|verification\\s*code|verification|passcode|security\\s*code|code)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 兼容短信中的全角冒号、中文提示语、括号和中间空格。
     * 例如：验证码：271460、验证码是 293940、verification code: 123456。
     */
    private val CODE_AFTER_KEYWORD_REGEX = Regex(
        "(?:验证码|动态码|授权码|校验码|验证代码|验证码是|verification\\s*code|verification|passcode|security\\s*code|code)" +
            "\\s*(?:是|为|is)?\\s*[:：=]?\\s*[\\(（\\[【{「]?\\s*((?:[0-9]{4,8})|(?:[A-Za-z0-9]{5,8}))\\s*[\\)）\\]】}」]?",
        RegexOption.IGNORE_CASE,
    )

    private val STANDALONE_CODE_REGEX = Regex(
        "(?<![0-9A-Za-z])[0-9]{4,8}(?![0-9A-Za-z])",
    )

    /**
     * 从短信正文提取验证码；优先取验证码关键词后的数字，格式特殊时再回退到独立数字。
     */
    fun extract(body: String): String? {
        val normalized = body.replace('\u00A0', ' ').trim()
        if (normalized.isBlank()) return null

        CODE_AFTER_KEYWORD_REGEX.find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val keyword = KEYWORD_REGEX.find(normalized) ?: return null
        // 仅在提示词后的短窗口内回退查找，避免把“15分钟有效”等时长数字误识别成验证码。
        val tail = normalized.substring(keyword.range.last + 1).take(40)
        return STANDALONE_CODE_REGEX.find(tail)?.value
    }

    /** 快速判断短信正文是否含验证码特征。 */
    fun contains(body: String): Boolean =
        body.isNotBlank() && KEYWORD_REGEX.containsMatchIn(body)

    /**
     * 跨短信接收器共享的去重闸门。
     *
     * SMS_RECEIVED 和通知监听可能同时收到同一条短信：前者收到短信广播，后者收到短信应用通知。
     * 之前两个 Receiver 各自维护去重状态，导致同一个验证码在约 1 秒内上传两次。
     */
    private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L
    private var lastForwardedCode: String? = null
    private var lastForwardedAtMs: Long = 0L

    @Synchronized
    fun shouldForward(code: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val normalized = code.trim()
        if (normalized.isBlank()) return false
        val elapsed = nowMs - lastForwardedAtMs
        if (normalized == lastForwardedCode && elapsed in 0 until DEDUP_WINDOW_MS) {
            return false
        }
        lastForwardedCode = normalized
        lastForwardedAtMs = nowMs
        return true
    }
}
