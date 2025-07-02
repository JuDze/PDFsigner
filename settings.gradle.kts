// settings.gradle.kts

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        // This line is crucial for resolving libraries hosted on JitPack, like mhiew:android-pdf-viewer
        maven("https://jitpack.io")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // This line is crucial for resolving libraries hosted on JitPack, like mhiew:android-pdf-viewer
        maven("https://jitpack.io")
    }
}

// Make sure this matches your project's root name
rootProject.name = "PDFsignerApp" // Assuming your project's root name is PDFsignerApp
include(":app") // This line should already be there for your app module
