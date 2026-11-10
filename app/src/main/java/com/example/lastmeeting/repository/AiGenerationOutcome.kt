package com.example.lastmeeting.repository

sealed interface AiGenerationOutcome {
    data class Stored(val text: String) : AiGenerationOutcome
    data object Discarded : AiGenerationOutcome
}
