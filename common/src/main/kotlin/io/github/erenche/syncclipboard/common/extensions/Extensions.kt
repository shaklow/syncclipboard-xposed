package io.github.erenche.syncclipboard.common.extensions

import android.content.Context
import android.content.SharedPreferences

val Context.defaultSharedPreferences: SharedPreferences
    get() = getSharedPreferences("${packageName}_preferences", Context.MODE_PRIVATE)

fun SharedPreferences.editCommit(block: SharedPreferences.Editor.() -> Unit) {
    edit().apply(block).commit()
}
