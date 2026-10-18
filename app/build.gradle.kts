plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub पर हर build का नंबर अपने-आप बढ़ता है, ताकि नया APK पुराने के ऊपर install हो
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.shivtej.aj"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shivtej.aj"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"

        // Cloud (Supabase) का पता: GitHub secrets से आता है, ताकि reinstall के बाद सिर्फ login करना पड़े।
        // न हो तो app के अंदर भी डाल सकते हैं।
        val supabaseUrl = System.getenv("AJ_SUPABASE_URL") ?: ""
        val supabaseKey = System.getenv("AJ_SUPABASE_ANON_KEY") ?: ""
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // हमेशा एक ही key से sign होगा, इसलिए update के लिए uninstall नहीं करना पड़ेगा (data बचा रहेगा)
    signingConfigs {
        create("aj") {
            storeFile = file("aj-release.keystore")
            storePassword = "ajassistant"
            keyAlias = "aj"
            keyPassword = "ajassistant"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("aj")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aj")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
