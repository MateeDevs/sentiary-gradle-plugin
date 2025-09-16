plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.pluginPublish) apply false
    alias(libs.plugins.serialization) apply false
}

allprojects {
    group = "sentiary"
    version = "1.0.0"
}