package com.example.domain

enum class FieldError { EMPTY, TOO_SHORT, TOO_LONG, INVALID_CHARS, MUST_START_WITH_LETTER, WEAK, MISMATCH }

object Validators {
    const val USERNAME_MIN = 3
    const val USERNAME_MAX = 20
    const val PASSWORD_MIN = 8
    const val DISPLAY_NAME_MAX = 50
    const val ABOUT_MAX = 140
    private val usernameChars = Regex("^[a-z0-9._]+$")
    private val tenDigits = Regex("^[1-9][0-9]{9}$")

    fun normalizeUsername(input: String): String = input.trim().removePrefix("@").lowercase()

    fun usernameError(raw: String): FieldError? {
        val u = normalizeUsername(raw)
        return when {
            u.isEmpty() -> FieldError.EMPTY
            u.length < USERNAME_MIN -> FieldError.TOO_SHORT
            u.length > USERNAME_MAX -> FieldError.TOO_LONG
            !usernameChars.matches(u) || u.contains("..") || u.endsWith(".") -> FieldError.INVALID_CHARS
            !u[0].isLetter() -> FieldError.MUST_START_WITH_LETTER
            else -> null
        }
    }

    fun passwordError(password: String): FieldError? = when {
        password.isEmpty() -> FieldError.EMPTY
        password.length < PASSWORD_MIN -> FieldError.TOO_SHORT
        password.length > 128 -> FieldError.TOO_LONG
        password.none { it.isLetter() } || password.none { it.isDigit() } -> FieldError.WEAK
        else -> null
    }

    fun confirmError(password: String, confirm: String): FieldError? = if (password != confirm) FieldError.MISMATCH else null

    fun displayNameError(name: String): FieldError? = when {
        name.isBlank() -> FieldError.EMPTY
        name.trim().length > DISPLAY_NAME_MAX -> FieldError.TOO_LONG
        else -> null
    }

    fun aboutError(about: String): FieldError? = if (about.trim().length > ABOUT_MAX) FieldError.TOO_LONG else null

    fun isTenDigitId(value: String): Boolean = tenDigits.matches(value.trim())
}
