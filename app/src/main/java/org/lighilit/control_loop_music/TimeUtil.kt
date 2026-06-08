//package com.example.controlloopmusic
package org.lighilit.control_loop_music

object TimeUtil {
    fun formatMs(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val hours = totalMinutes / 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    fun parseMs(text: String): Long {
        val parts = text.trim().split(":")
        require(parts.isNotEmpty() && parts.size <= 3) { "Use SS, MM:SS, or HH:MM:SS" }
        require(parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) { "Use digits and ':'" }

        val hours: Long
        val minutes: Long
        val seconds: Long
        when (parts.size) {
            1 -> {
                hours = 0
                minutes = 0
                seconds = parts[0].toLong()
            }
            2 -> {
                hours = 0
                minutes = parts[0].toLong()
                seconds = parts[1].toLong()
            }
            else -> {
                hours = parts[0].toLong()
                minutes = parts[1].toLong()
                seconds = parts[2].toLong()
            }
        }

        require(minutes < 60 && seconds < 60) { "Minutes and seconds must be below 60" }
        return ((hours * 60 + minutes) * 60 + seconds) * 1000
    }

    fun parseRangeMs(text: String): LongArray {
        val parts = text.replace("Loop:", "").trim().split("-", limit = 2)
        require(parts.size == 2) { "Use a range like 00:00-00:28" }
        return longArrayOf(parseMs(parts[0]), parseMs(parts[1]))
    }
}
