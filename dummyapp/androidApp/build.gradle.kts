plugins {
    id("com.android.application")
}

android {
    namespace = "dev.lynx.dummyapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lynx.dummyapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
