package io.github.erenche.syncclipboard.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import io.github.erenche.syncclipboard.app.R
import io.github.erenche.syncclipboard.common.extensions.defaultSharedPreferences
import io.github.erenche.syncclipboard.common.extensions.editCommit
import java.util.Locale

/**
 * 应用语言工具 — 管理用户自定义语言选择。
 *
 * 使用 SharedPreferences 持久化语言偏好。
 * [DEFAULT_LANGUAGE] 表示跟随系统。
 */
object AppLangUtils {
    private const val KEY_LANGUAGE = "language"

    /** 跟随系统的语言标识（空字符串，与历史版本兼容）。 */
    const val DEFAULT_LANGUAGE: String = ""

    @SuppressLint("ConstantLocale")
    val DEFAULT_LOCALE: Locale = Locale.getDefault()

    /** 包裹 Context 以应用用户选择的语言。 */
    fun wrapContext(context: Context): Context =
        wrapContext(context, getCustomizeLang(context))

    /** 将默认 Locale 设置为用户选择的语言（用于全局生效）。 */
    fun setDefaultLocale(context: Context) {
        val locale = forLanguageTag(getCustomizeLang(context))
        Locale.setDefault(locale)
    }

    /** 获取用户选择的语言对应的 [Locale]。 */
    fun getLocale(context: Context): Locale =
        forLanguageTag(getCustomizeLang(context))

    /** 读取用户自定义语言，未设置时返回 [DEFAULT_LANGUAGE]。 */
    fun getCustomizeLang(context: Context): String =
        context.defaultSharedPreferences
            .getString(KEY_LANGUAGE, DEFAULT_LANGUAGE)
            ?: DEFAULT_LANGUAGE

    /** 保存用户自定义语言。 */
    fun saveCustomizeLanguage(context: Context, language: String) {
        context.defaultSharedPreferences.editCommit {
            putString(KEY_LANGUAGE, language)
        }
    }

    private fun forLanguageTag(language: String): Locale {
        if (language.isBlank() || language == DEFAULT_LANGUAGE) return DEFAULT_LOCALE
        return runCatching {
            if (language.contains("-")) {
                val parts = language.split("-")
                Locale(parts[0], parts[1])
            } else {
                Locale(language)
            }
        }.getOrDefault(DEFAULT_LOCALE)
    }

    @SuppressLint("AppBundleLocaleChanges")
    private fun wrapContext(context: Context, language: String): Context {
        val locale = forLanguageTag(language)
        val config = context.resources.configuration
        config.setLocale(locale)
        return Cold(context.createConfigurationContext(config))
    }

    class Cold(base: Context) : ContextWrapper(base)
}

/**
 * 解析语言代码为可读的显示名称。
 *
 * @param displayLocale 用于显示名称的 Locale，默认使用语言自身的 Locale。
 */
fun Context.resolveLanguageName(
    languageCode: String,
    displayLocale: Locale? = null
): String {
    if (languageCode.isBlank() || languageCode == AppLangUtils.DEFAULT_LANGUAGE) {
        return getString(R.string.setting_language_auto)
    }
    return runCatching {
        val locale = Locale.forLanguageTag(languageCode)
        locale.getDisplayName(displayLocale ?: locale)
            .replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(locale) else it.toString()
            }
    }.getOrDefault(languageCode)
}
