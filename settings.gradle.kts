println("=== DIAG: settings.gradle.kts executing ===")
println("Java version: ${System.getProperty("java.version")}")
println("Java home: ${System.getProperty("java.home")}")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AI-Live-Overflow"
include(":app")
