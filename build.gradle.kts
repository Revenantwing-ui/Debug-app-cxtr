// ROOT build.gradle.kts
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Defines the plugin versions for the whole project
        classpath("com.android.tools.build:gradle:8.2.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
    }
}

// Do NOT include 'allprojects' or 'plugins' blocks here.
// Repositories are handled in settings.gradle.kts
