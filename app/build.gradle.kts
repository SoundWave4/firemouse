plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.firemouse"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.firemouse"
        minSdk = 30        // Fire OS 8 = Android 11 = API 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
