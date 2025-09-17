package com.sentiary.config

/**
 * A singleton, serializable naming strategy for folders.
 * Returns "values" for the default language and "values-{language}" for others.
 */
internal object DefaultFolderNamingStrategy : NamingStrategy {
    override fun invoke(language: String, isDefault: Boolean): String {
        return if (isDefault) "values" else "values-$language"
    }
}

/**
 * A serializable naming strategy for files that derives the filename from the chosen [Format].
 */
internal data class FileNamingStrategyFromFormat(val format: Format) : NamingStrategy {
    override fun invoke(language: String, isDefault: Boolean): String = format.fileName
}
