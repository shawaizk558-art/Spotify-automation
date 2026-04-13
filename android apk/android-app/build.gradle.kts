plugins {
    id("com.android.application")
}


android {
    namespace = "com.example.spotifybot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.spotifybot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
    }
    sourceSets {
        getByName("main") {
            manifest.srcFile("app/src/main/AndroidManifest.xml")
            java.setSrcDirs(listOf("app/src/main/java"))
            res.setSrcDirs(listOf("app/src/main/res"))
        }
    }
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
}
