package io.github.erenche.syncclipboard.app.compose.preference

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** Remember a boolean preference backed by SharedPreferences */
@Composable
fun rememberBooleanPreference(
    prefs: SharedPreferences,
    key: String,
    defaultValue: Boolean
): MutableState<Boolean> {
    val state = remember { mutableStateOf(prefs.getBoolean(key, defaultValue)) }
    return object : MutableState<Boolean> by state {
        override var value: Boolean
            get() = state.value
            set(newValue) {
                state.value = newValue
                prefs.edit().putBoolean(key, newValue).apply()
            }
    }
}

/** Remember a string preference backed by SharedPreferences */
@Composable
fun rememberStringPreference(
    prefs: SharedPreferences,
    key: String,
    defaultValue: String
): MutableState<String> {
    val state = remember { mutableStateOf(prefs.getString(key, defaultValue) ?: defaultValue) }
    return object : MutableState<String> by state {
        override var value: String
            get() = state.value
            set(newValue) {
                state.value = newValue
                prefs.edit().putString(key, newValue).apply()
            }
    }
}
