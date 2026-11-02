package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.json.JSONObject

internal enum class PreparationStop { RESERVED, PUBLISHED }

/** Fail only a scoped synthetic store at a real persistence boundary; no production fault hooks. */
internal fun preparationFaultContext(base: Context, stop: PreparationStop): Context = object : ContextWrapper(base) {
    private var armed = true
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val actual = super.getSharedPreferences(name, mode)
        if (name != "obsidian_connections") return actual
        return object : SharedPreferences by actual {
            override fun edit(): SharedPreferences.Editor {
                val delegate = actual.edit(); var pending = false
                return object : SharedPreferences.Editor by delegate {
                    override fun clear(): SharedPreferences.Editor { delegate.clear(); return this }
                    override fun putString(key: String, value: String?): SharedPreferences.Editor {
                        pending = value?.let { JSONObject(it).getInt("schema") == 2 } == true
                        delegate.putString(key, value); return this
                    }
                    override fun commit(): Boolean {
                        val saved = delegate.commit()
                        if (armed && saved && pending && stop == PreparationStop.RESERVED) {
                            armed = false; error("Fixture interruption after private reservation")
                        }
                        if (armed && saved && !pending && stop == PreparationStop.PUBLISHED) {
                            armed = false; return false
                        }
                        return saved
                    }
                }
            }
        }
    }
}
