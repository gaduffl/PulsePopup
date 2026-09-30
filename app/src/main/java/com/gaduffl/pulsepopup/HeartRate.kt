package com.gaduffl.pulsepopup

import java.util.UUID

/** Standard Bluetooth "Heart Rate" profile (used by practically every chest strap). */
object HeartRate {
    val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Parses a Heart Rate Measurement characteristic value; returns -1 if malformed. */
    fun parse(value: ByteArray): Int {
        if (value.size < 2) return -1
        val flags = value[0].toInt()
        return if ((flags and 0x01) == 0) {
            value[1].toInt() and 0xFF
        } else {
            if (value.size < 3) return -1
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        }
    }
}
