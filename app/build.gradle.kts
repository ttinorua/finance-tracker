import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
/** A build secret from local.properties, or else the environment (a cloud build environment's
 * variables). */
fun buildSecret(name: String): String? =
    (localProperties.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

// The app's own Anthropic key. FINANCE_APP_ANTHROPIC_API_KEY is checked first because the Claude
// Code cloud environment keeps ANTHROPIC_* variables for its own connection and doesn't pass an
// ANTHROPIC_API_KEY through to builds.
val anthropicApiKey: String =
    (buildSecret("FINANCE_APP_ANTHROPIC_API_KEY") ?: buildSecret("ANTHROPIC_API_KEY"))?.trim() ?: ""

// Enable Banking (Sydbank open-banking sync). The application ID isn't secret — it's sent in the
// clear with every request and is useless without the private key — so the app's registered ID
// is the default. The app expects the private key base64-encoded (of the whole PEM text): either
// supply that as ENABLE_BANKING_PRIVATE_KEY_B64, or paste the .pem file's text as-is into
// ENABLE_BANKING_PRIVATE_KEY and it's encoded here.
// Shared free Gemini (Google AI Studio) and Groq keys, so AI works out of the box for everyone the
// app is shared with. Optional; each person can also enter their own keys in Settings.
val geminiApiKey: String = buildSecret("GEMINI_API_KEY")?.trim() ?: ""
val groqApiKey: String = buildSecret("GROQ_API_KEY")?.trim() ?: ""

// OneDrive automatic backup: the "Application (client) ID" of the app's Microsoft registration
// (multitenant + personal accounts, redirect financetracker://onedrive-auth). Not a secret — it's
// sent in the clear during sign-in — so the app's own registration is the default.
val oneDriveClientId: String =
    buildSecret("ONEDRIVE_CLIENT_ID")?.trim() ?: "695700a7-d16d-41f5-96cd-d136232061bc"

// Household budget (shared with a partner) runs on the app's own Firebase project. These identify
// the project and aren't secret on their own (they ship inside every Firebase app; access is guarded
// by the Firestore security rules), but they're kept in the build environment rather than in this
// public repository. Without them the household feature says it isn't set up.
val firebaseApiKey: String = buildSecret("FIREBASE_API_KEY")?.trim() ?: ""
val firebaseAppId: String = buildSecret("FIREBASE_APP_ID")?.trim() ?: ""
val firebaseProjectId: String = buildSecret("FIREBASE_PROJECT_ID")?.trim() ?: ""

val enableBankingApplicationId: String =
    buildSecret("ENABLE_BANKING_APPLICATION_ID")?.trim() ?: "7bf8c383-b4b9-41c5-b126-11cc4f76c1c5"
val enableBankingPrivateKeyB64: String =
    buildSecret("ENABLE_BANKING_PRIVATE_KEY_B64")?.trim()
        ?: buildSecret("ENABLE_BANKING_PRIVATE_KEY")
            // An env var editor may keep the line breaks as literal "\n" text.
            ?.replace("\\n", "\n")
            ?.trim()
            ?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
        ?: ""

android {
    lint {
        // The release build's own quick lint pass ("lintVital", ~2 minutes on the cloud build machine)
        // repeats what `lintDebug` already checks before every push, so it's skipped.
        checkReleaseBuilds = false
    }
    namespace = "com.financetracker.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.financetracker.app"
        minSdk = 26
        targetSdk = 34
        // Each build gets its own number from its build time (Danish time), so you can tell which
        // APK is installed (Settings, at the bottom). The code keeps rising, so updates install.
        val builtAt = ZonedDateTime.now(ZoneId.of("Europe/Copenhagen"))
        versionCode = ((builtAt.toEpochSecond() - 1_767_225_600L) / 60).toInt() // minutes since 1 Jan 2026
        versionName = "1.1 · " + builtAt.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.US))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
        buildConfigField("String", "ENABLE_BANKING_APPLICATION_ID", "\"$enableBankingApplicationId\"")
        buildConfigField("String", "ENABLE_BANKING_PRIVATE_KEY_B64", "\"$enableBankingPrivateKeyB64\"")
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"$oneDriveClientId\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseAppId\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
    }

    // The app's permanent signing key. Android only installs an update signed with the same key as
    // the installed app, so this must never change — it lives in the build environment
    // (ANDROID_KEYSTORE_B64 = the .p12 file base64-encoded, ANDROID_KEYSTORE_PASSWORD), never in
    // this repository. Without it, release builds come out unsigned.
    val releaseKeystoreB64 = buildSecret("ANDROID_KEYSTORE_B64")
    val releaseKeystorePassword = buildSecret("ANDROID_KEYSTORE_PASSWORD")
    if (releaseKeystoreB64 != null && releaseKeystorePassword != null) {
        signingConfigs {
            create("release") {
                val keystoreFile = layout.buildDirectory.file("signing/release.p12").get().asFile
                keystoreFile.parentFile.mkdirs()
                keystoreFile.writeBytes(Base64.getMimeDecoder().decode(releaseKeystoreB64.trim()))
                storeFile = keystoreFile
                storeType = "pkcs12"
                storePassword = releaseKeystorePassword.trim()
                keyAlias = "financetracker"
                keyPassword = releaseKeystorePassword.trim()
            }
        }
    }

    buildTypes {
        release {
            // Code shrinking (R8) is off: on the 4-core cloud build machine it took over an
            // hour for this app (mostly the big Apache POI spreadsheet library), and it has caused
            // several bugs that only showed up in the installed app. The cost is a bigger APK.
            // The rules in proguard-rules.pro stay in case shrinking is turned back on.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Company/bank logos on transactions and bank connections
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Reads which way up a chosen photo is, for logos from the phone's pictures.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Firebase: the household budget shared with a partner (anonymous sign-in + Firestore).
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // WorkManager: lets the AI categorization backfill keep running (and be observed) across
    // navigation, screen-off, and app backgrounding instead of dying with the ViewModel.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Apache POI for Excel (.xlsx) import/export
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5") {
        exclude(group = "org.apache.xmlbeans", module = "xmlbeans")
    }
    implementation("org.apache.xmlbeans:xmlbeans:5.2.0")

    // Google sign-in for the automatic backup to Google Drive
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Claude API (chat, category suggestions, spending insights)
    implementation("com.anthropic:anthropic-java:2.52.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM unit tests (Android's own copy is a stub there).
    testImplementation("org.json:json:20240303")
    // Runs database upgrade tests on the JVM against a real SQLite.
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Robolectric downloads its Android runtime jar itself (not through the repositories in
// settings.gradle.kts); point it at Google's copy of Maven Central too, which doesn't turn fresh
// cloud build machines away with HTTP 429 the way Maven Central does.
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
}
