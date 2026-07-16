package io.github.erenche.syncclipboard.common

import io.github.erenche.syncclipboard.common.BuildConfig

/**
 * 包名常量
 */
object PackageNames {
    /** 本 App 的包名 */
    val APPLICATION: String get() = BuildConfig.APP_PACKAGE_NAME

    /** SystemUI 的包名 */
    const val SYSTEM_UI = "com.android.systemui"

    /** Android 系统（system_server） */
    const val ANDROID = "android"
}
