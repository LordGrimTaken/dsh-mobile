package dev.dsh.mobile.data

import android.content.Context

/** Minimal key/value surface so [HostProfileStore] can be unit tested without Android. */
interface HostKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/** Android implementation backed by app-private SharedPreferences. */
class SharedPreferencesHostKeyValueStore(
    context: Context,
    prefsName: String = "dsh_mobile_hosts",
) : HostKeyValueStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
