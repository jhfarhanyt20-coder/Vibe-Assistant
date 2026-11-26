package com.vibe.assistant

import android.content.SharedPreferences

/** In-memory SharedPreferences for PC tests. */
class MemPrefs : SharedPreferences {
    private val m = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(m)
    override fun getString(key: String?, defValue: String?) = (m[key] as? String) ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = (m[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int) = (m[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long) = (m[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float) = (m[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = (m[key] as? Boolean) ?: defValue
    override fun contains(key: String?) = m.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val put = HashMap<String, Any?>()
        private val del = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { put[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { put[key] = values }
        override fun putInt(key: String, value: Int) = apply { put[key] = value }
        override fun putLong(key: String, value: Long) = apply { put[key] = value }
        override fun putFloat(key: String, value: Float) = apply { put[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { put[key] = value }
        override fun remove(key: String) = apply { del += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) m.clear()
            del.forEach { m.remove(it) }
            m.putAll(put)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
