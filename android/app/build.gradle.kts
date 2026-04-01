import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("com.google.dagger.hilt.android")
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "com.bontecou.syncmd"
    compileSdk = 35
    
    defaultConfig {
        applicationId = "com.bontecou.syncmd"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.0"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) {
            f.inputStream().use(::load)
        }
    }

    val releaseStoreFilePath =
        System.getenv("RELEASE_STORE_FILE") ?: localProps.getProperty("RELEASE_STORE_FILE")
    val releaseStorePassword =
        System.getenv("RELEASE_STORE_PASSWORD") ?: localProps.getProperty("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias =
        System.getenv("RELEASE_KEY_ALIAS") ?: localProps.getProperty("RELEASE_KEY_ALIAS")
    val releaseKeyPassword =
        System.getenv("RELEASE_KEY_PASSWORD") ?: localProps.getProperty("RELEASE_KEY_PASSWORD")

    signingConfigs {
        create("release") {
            if (!releaseStoreFilePath.isNullOrBlank()) {
                storeFile = file(releaseStoreFilePath)
            }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            if (
                !releaseStoreFilePath.isNullOrBlank() &&
                    !releaseStorePassword.isNullOrBlank() &&
                    !releaseKeyAlias.isNullOrBlank() &&
                    !releaseKeyPassword.isNullOrBlank()
            ) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Release signing not configured. Set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD (local.properties or env)."
                )
            }

            isMinifyEnabled = true
            isShrinkResources = true
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
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.6"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // JGit ships duplicate licence/notice files
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/eclipse.inf"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

play {
    val configuredPath =
        System.getenv("PLAY_CONSOLE_KEY_PATH")
            ?: providers.gradleProperty("PLAY_CONSOLE_KEY_PATH").orNull
            ?: "${System.getProperty("user.home")}/.config/play-console/play-publisher-crested-drive-492000-u7.json"

    val serviceKeyFile = file(configuredPath)
    if (serviceKeyFile.exists()) {
        serviceAccountCredentials.set(serviceKeyFile)
    } else {
        logger.warn("Play Console key not found at: $configuredPath")
    }

    track.set("internal")
    defaultToAppBundles.set(true)
}

dependencies {
    // Project modules
    implementation(project(":core"))
    
    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Lifecycle and ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Activity
    implementation(libs.androidx.activity.compose)
    
    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    
    // Browser (Custom Tabs for GitHub OAuth)
    implementation(libs.androidx.browser)

    // Google Play Billing
    implementation(libs.billing)

    // Coroutines
    implementation(libs.coroutines.android)
    
    // Android core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.com.google.android.material)
    
    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(composeBom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
