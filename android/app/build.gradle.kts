import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("com.google.dagger.hilt.android")
    alias(libs.plugins.play.publisher)
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        f.inputStream().use(::load)
    }
}

val secretValue: (String) -> String? = { key ->
    System.getenv(key)
        ?: providers.gradleProperty(key).orNull
        ?: keystoreProps.getProperty(key)
}

android {
    namespace = "com.bontecou.syncmd"
    compileSdk = 36
    
    defaultConfig {
        // Store identity for the paid-upfront listing. The original free listing
        // (com.bontecou.syncmd) is permanently free per Play policy and is being
        // unpublished; the code namespace above intentionally stays unchanged.
        applicationId = "md.gitsync.md"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "1.0.3"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    
    val releaseStoreFilePath = secretValue("RELEASE_STORE_FILE")
    val releaseStorePassword = secretValue("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = secretValue("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = secretValue("RELEASE_KEY_PASSWORD")

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
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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
                    "Release signing not configured. Set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD (env, gradle.properties, or keystore.properties)."
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
            excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

play {
    val configuredPath =
        secretValue("PLAY_CONSOLE_KEY_PATH")
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
    implementation(libs.gson)
    implementation("com.google.firebase:firebase-messaging:25.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    
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
    testImplementation(libs.jgit)
    androidTestImplementation(libs.jgit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(platform(composeBom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
