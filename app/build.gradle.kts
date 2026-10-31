plugins { id("com.android.application") }
android {
    namespace = "br.com.mi.atv.launcher"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.mi.atv.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "5.0-tvOS"
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
