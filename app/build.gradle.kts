import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("dev.rikka.tools.refine") version "4.4.0"
}

android {
    namespace = "dev.pranav.applock"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.pranav.applock"
        minSdk = 26
        targetSdk = 37
        versionCode = 251
        versionName = "2.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI signs with a keystore supplied through the environment when one is configured, so that
    // successive builds share a signature and install over each other. Without it the build falls
    // back to the debug key, which is regenerated per machine and per CI run.
    val keystorePath: String? = System.getenv("APPLOCK_KEYSTORE")

    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("APPLOCK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("APPLOCK_KEY_ALIAS")
                keyPassword = System.getenv("APPLOCK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            vcsInfo.include = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (!keystorePath.isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin.compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    buildFeatures {
        compose = true
        aidl = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":appintro"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.refine.runtime)
    compileOnly(project(":hidden-api"))
    implementation(libs.hiddenapibypass)
    implementation(project(":patternlock"))



    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
