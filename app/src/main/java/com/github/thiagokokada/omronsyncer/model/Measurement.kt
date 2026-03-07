package com.github.thiagokokada.omronsyncer.model

import java.time.LocalDateTime

data class Measurement(
    val user: Int,
    val recordedAt: LocalDateTime,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int,
    val irregularHeartbeat: Boolean,
    val movement: Boolean,
) {
    fun flagsLabel(): String {
        val flags = buildList {
            if (irregularHeartbeat) add("IHB")
            if (movement) add("MOV")
        }
        return if (flags.isEmpty()) "-" else flags.joinToString("/")
    }
}
