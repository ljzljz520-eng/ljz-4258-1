plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.dairy.receiving.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dairy.receiving.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core"))

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// 离线环境/ARM 主机无 aapt2 时，仅下载全部传递 jar/aar（不触资源处理）
val resolveAll by configurations.creating
dependencies {
    resolveAll("androidx.core:core-ktx:1.13.1")
    resolveAll("androidx.activity:activity-compose:1.9.2")
    resolveAll("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    resolveAll("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    resolveAll("androidx.room:room-runtime:2.6.1")
    resolveAll("androidx.room:room-ktx:2.6.1")
    resolveAll(platform("androidx.compose:compose-bom:2024.09.02"))
    resolveAll("androidx.compose.ui:ui")
    resolveAll("androidx.compose.material3:material3")
    resolveAll("androidx.compose.material:material-icons-extended")
    resolveAll("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
tasks.register("resolveAllArtifacts") {
    doLast { println("RESOLVED " + resolveAll.resolvedConfiguration.resolvedArtifacts.size) }
}
