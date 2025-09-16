package com.sentiary.config

import java.io.Serializable

/**
 * Represents the different formats that can be exported.
 * Each format has an API name and a default file name.
 */
sealed interface Format : Serializable {

    /**
     * The name of the format as it is used in the API.
     */
    val apiName: String

    /**
     * The name of the file that will be generated. Can be overridden by the [ExportPath.fileNamingStrategy].
     */
    val fileName: String

    object Apple : Format {
        override val apiName = "apple"
        override val fileName = "Localizable.strings"
    }

    object Android : Format {
        override val apiName = "android"
        override val fileName = "strings.xml"
    }

    object ComposeResources : Format {
        override val apiName = "compose"
        override val fileName = "strings.xml"
    }

    object Json : Format {
        override val apiName = "json"
        override val fileName = "strings.json"
    }
}