package com.tripshare.app.location

import android.content.Context

object AmapConsentStore {
    private const val PREFS = "amap_privacy_consent"
    private const val KEY_ACCEPTED = "accepted"

    fun isAccepted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)

    fun accept(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACCEPTED, true).apply()
    }
}

object LocationDisclosureStore {
    private const val PREFS = "location_disclosure"
    private const val KEY_ACCEPTED = "accepted"

    fun isAccepted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)

    fun accept(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACCEPTED, true).apply()
    }
}
