plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "su.myt.home"
    compileSdk = 35
    defaultConfig {
        applicationId = "su.myt.home"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.4"
        providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull()?.let { versionCode = it }
        providers.gradleProperty("releaseVersion").orNull?.let { versionName = it }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
}
