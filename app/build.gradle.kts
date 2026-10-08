plugins { id("com.android.application") }

android {
    namespace = "com.larastudio.pdfku"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.larastudio.pdfku"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.3.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    dependencies {
        implementation("androidx.core:core:1.17.0")
        implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
