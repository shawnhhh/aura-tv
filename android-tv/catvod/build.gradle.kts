plugins { id("com.android.library") }

android {
    namespace = "com.github.catvod.crawler"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

// Source from FongMi/TV fongmi@123cc086159b0f13bf54bdf64bacbc259ca47c88,
// GPL-3.0. Dependency versions follow that upstream release, with Java 17
// compatibility adjustments documented in docs/CATVOD_GPL.md.
dependencies {
    api("androidx.annotation:annotation:1.9.1")
    api("org.brotli:dec:0.1.2")
    // OkHttp 5.5 needs Android SDK 37; HERRIN preserves its tested SDK 35
    // for TCL/API 26+ compatibility. These okhttp3 APIs are in 4.12 too.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    api("com.squareup.okhttp3:logging-interceptor:4.12.0")
    api("com.google.code.gson:gson:2.14.0")
    api("com.google.guava:guava:33.7.2-android")
    api("com.googlecode.juniversalchardet:juniversalchardet:1.0.3")
    api("com.orhanobut:logger:2.2.0")
    api("androidx.preference:preference:1.2.1")
    api("com.github.thegrizzlylabs:sardine-android:0.9")
    api("com.hierynomus:smbj:0.15.0")
    api("androidx.startup:startup-runtime:1.2.0")
    api("com.google.zxing:core:3.5.4")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
