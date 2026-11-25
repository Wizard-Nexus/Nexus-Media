import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "Nexus Media"
include(":app")
include(":baselineprofile")
val localFfmpegProperties = Properties().apply {
    val localFile = settingsDir.resolve("local.properties")
    if (localFile.isFile) localFile.inputStream().use(::load)
}
val localFfmpegSetting = providers.gradleProperty("useLocalFfmpegDecoder").orNull
    ?: providers.environmentVariable("USE_LOCAL_FFMPEG_DECODER").orNull
    ?: localFfmpegProperties.getProperty("USE_LOCAL_FFMPEG_DECODER")
val buildingLocalFfmpegModule = gradle.startParameter.taskNames.any {
    it.removePrefix(":").startsWith("ffmpeg-decoder-downmix:")
}
if (localFfmpegSetting?.lowercase() in setOf("true", "1", "yes") || buildingLocalFfmpegModule) {
    include(":ffmpeg-decoder-downmix")
}
