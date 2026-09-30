package com.gaduffl.pulsepopup

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val KEY_ADDRESS = "address"
    const val KEY_NAME = "name"
    const val KEY_SHOW_TIMER = "show_timer"
    const val KEY_X = "overlay_x"
    const val KEY_Y = "overlay_y"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences("pulsepopup", Context.MODE_PRIVATE)
}
