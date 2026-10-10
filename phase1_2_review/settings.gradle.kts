pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "IncidentAssistant"
include(":app", ":core:audio", ":core:inference")
if (providers.gradleProperty("nativeModels").orNull == "true") {
    include(":core:native-whisper", ":core:native-llama")
}
