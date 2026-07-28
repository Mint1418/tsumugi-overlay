plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}

tasks.register("setupAndroidSdk") {
    doLast {
        val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: "/usr/lib/android-sdk"
        val sdkDir = file(sdkRoot)
        if (!sdkDir.resolve("platforms/android-34").exists()) {
            println("Downloading Android SDK platform 34...")
            // Try to use sdkmanager
            val sdkmanager = sdkDir.resolve("cmdline-tools/latest/bin/sdkmanager")
            if (sdkmanager.exists()) {
                exec {
                    commandLine(sdkdkmanager.absolutePath, "platforms;android-34", "build-tools;34.0.0")
                }
            } else {
                // Download cmdline-tools
                val url = java.net.URL("https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip")
                val zipFile = file("/tmp/cmdline-tools.zip")
                url.openStream().use { input ->
                    zipFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                println("Downloaded cmdline-tools, please run again")
            }
        }
    }
}