package com.sentiary.model

internal data class LanguageConfiguration(
    val languagesToFetch: Set<String>,
    val expectedLanguages: Set<String>,
)