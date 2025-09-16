package com.sentiary.config

import java.io.Serializable

fun interface NamingStrategy : Serializable {
    operator fun invoke(language: String, isDefault: Boolean): String
}