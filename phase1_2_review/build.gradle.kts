plugins {
    id("com.android.application") version "8.5.0" apply false
    id("com.android.library") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}

// Opt-in: build outside a synced folder (OneDrive holds files Gradle must delete). Set in ~/.gradle/gradle.properties.
providers.gradleProperty("incidentAssistant.buildRoot").orNull?.let { root ->
    allprojects { layout.buildDirectory.set(file("$root/${if (path == ":") "root" else path.removePrefix(":").replace(':', '/')}")) }
}
