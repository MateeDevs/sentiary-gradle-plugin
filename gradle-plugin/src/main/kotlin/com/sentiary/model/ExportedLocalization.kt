package com.sentiary.model

import com.sentiary.config.ExportPath
import java.io.File

internal data class ExportedLocalization(
    val language: String,
    val outputFile: File,
    val exportPath: ExportPath,
)