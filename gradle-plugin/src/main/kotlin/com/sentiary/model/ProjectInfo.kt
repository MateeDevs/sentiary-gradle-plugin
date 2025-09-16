package com.sentiary.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ProjectInfo(
    val id: String,
    val name: String,
    val languages: List<String>,
    val termsLastModified: Instant = Clock.System.now(),
)