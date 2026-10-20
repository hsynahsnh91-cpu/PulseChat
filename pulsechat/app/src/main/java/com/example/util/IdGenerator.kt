package com.example.util

import kotlin.random.Random

object IdGenerator {
    /**
     * Generates a unique 10-digit numerical ID.
     */
    fun generate10DigitId(): String {
        val firstDigit = Random.nextInt(1, 10) // 1..9 so it never has leading zero
        val remaining = (1..9).map { Random.nextInt(0, 10) }.joinToString("")
        return "$firstDigit$remaining"
    }
}
