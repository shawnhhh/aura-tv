plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "tv.herrin.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "tv.herrin.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "0.4.31-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    // Optional stable self-distribution signature. Never commit keys or passwords.
    // Missing values produce normal debug builds; the release workflow refuses
    // to upload unsigned APKs.
    val releaseStorePath = System.getenv("HERRIN_RELEASE_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("HERRIN_RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("HERRIN_RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("HERRIN_RELEASE_KEY_PASSWORD")
    if (listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
            .all { !it.isNullOrBlank() }) {
        signingConfigs.create("herrinRelease") {
            storeFile = file(releaseStorePath!!)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("herrinRelease")
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // GPL-3.0 CatVod SDK is source-compiled in this app module to avoid
    // AGP 8.7/D8 cross-module dexing failures; source stays in its own packages.
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.brotli:dec:0.1.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("com.googlecode.juniversalchardet:juniversalchardet:1.0.3")
    implementation("com.orhanobut:logger:2.2.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.github.thegrizzlylabs:sardine-android:0.9")
    implementation("com.hierynomus:smbj:0.15.0")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Stable Media3 modules provide a real playback engine for an explicitly authorized source.
    // No demo stream or unauthorized provider is bundled.
    val media3 = "1.9.4"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
