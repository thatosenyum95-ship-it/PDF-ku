plugins { id("com.android.application") }

android {
    namespace = "com.larastudio.pdfku"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.larastudio.pdfku"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    dependencies { implementation("androidx.core:core:1.17.0") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
