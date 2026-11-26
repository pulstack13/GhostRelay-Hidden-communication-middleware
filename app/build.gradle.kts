import java.util.Properties
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val storeConfig = Properties().apply {
    val configFile = rootProject.file("store.properties")
    if (!configFile.exists()) {
        error("Missing store.properties. Copy store.properties.example to store.properties and add your Supabase URL, publishable key, and 4–12 digit app.lockCode.")
    }
    configFile.inputStream().use(::load)
}
val supabaseUrl = storeConfig.getProperty("supabase.url", "").trim()
val supabasePublishableKey = storeConfig.getProperty("supabase.publishableKey", "").trim()
val appLockCode = storeConfig.getProperty("app.lockCode", "").trim()
fun isPublishableSupabaseKey(key: String): Boolean {
    if (key.startsWith("sb_publishable_")) return true
    if (!key.startsWith("eyJ")) return false
    val segments = key.split('.')
    if (segments.size != 3) return false
    val payload = runCatching {
        String(Base64.getUrlDecoder().decode(segments[1]), Charsets.UTF_8)
    }.getOrNull() ?: return false
    return Regex("\"role\"\\s*:\\s*\"anon\"").containsMatchIn(payload)
}
require(supabaseUrl.startsWith("https://") && !supabaseUrl.contains("YOUR_PROJECT_ID")) {
    "Set supabase.url to your HTTPS Supabase project URL in store.properties."
}
require(isPublishableSupabaseKey(supabasePublishableKey)) {
    "Set supabase.publishableKey to a Supabase publishable key (sb_publishable_...) or legacy anon JWT in store.properties. Secret/service-role keys are rejected."
}
require(appLockCode.matches(Regex("[0-9]{4,12}"))) {
    "Set app.lockCode to your own 4–12 digit code in store.properties."
}
fun quotedBuildValue(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "dev.nook.messenger"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.nook.messenger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SUPABASE_URL", quotedBuildValue(supabaseUrl))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", quotedBuildValue(supabasePublishableKey))
        buildConfigField("String", "APP_LOCK_CODE", quotedBuildValue(appLockCode))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    configurations.configureEach {
        resolutionStrategy.force("androidx.browser:browser:1.8.0")
    }
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.5.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-android:3.0.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
