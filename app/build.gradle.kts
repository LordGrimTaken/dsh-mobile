import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Declared here (not in the root build) so app/ stays self-contained.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

/**
 * Play upload signing. Credentials are never committed: read `keystore.properties` from
 * DSH_KEYSTORE_PROPERTIES, the project root, or the main checkout when building inside a
 * `.agents/worktrees/<task>` worktree (`rootProject/../../..`).
 */
data class SigningCredentials(val properties: Properties, val propertiesFile: File)

fun findSigningCredentials(): SigningCredentials? {
    val candidates = buildList {
        System.getenv("DSH_KEYSTORE_PROPERTIES")
            ?.takeIf { it.isNotBlank() }
            ?.let { add(File(it)) }
        add(rootProject.file("keystore.properties"))
        add(rootProject.file("../../..").resolve("keystore.properties"))
    }
    val propertiesFile = candidates.firstOrNull { it.isFile } ?: return null
    val properties = Properties()
    propertiesFile.inputStream().use { properties.load(it) }
    return SigningCredentials(properties, propertiesFile)
}

val signingCredentials = findSigningCredentials()
if (signingCredentials == null) {
    logger.warn(
        "Play signing credentials not found (DSH_KEYSTORE_PROPERTIES/keystore.properties); " +
            "release bundle will be UNSIGNED.",
    )
}

android {
    namespace = "dev.dsh.mobile"
    compileSdk = 36

    // Google Play builds pass -PplayApplicationId=com.dsh.unofficial.app. The default keeps the
    // existing sideload/debug package so the public APK update path is unchanged.
    val playApplicationId = providers.gradleProperty("playApplicationId").orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "dev.dsh.mobile"

    defaultConfig {
        applicationId = playApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 152
        versionName = "0.5.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            signingCredentials?.let { credentials ->
                val rawStoreFile = credentials.properties.getProperty("storeFile").orEmpty()
                val storeFileCandidate = File(rawStoreFile)
                storeFile = if (storeFileCandidate.isAbsolute) {
                    storeFileCandidate
                } else {
                    credentials.propertiesFile.parentFile.resolve(rawStoreFile)
                }
                storePassword = credentials.properties.getProperty("storePassword")
                keyAlias = credentials.properties.getProperty("keyAlias")
                keyPassword = credentials.properties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Debug/sideload builds keep the update feed and APK installer.
            buildConfigField("boolean", "PLAY_BUILD", "false")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "PLAY_BUILD", "true")
            if (signingCredentials != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        // BuildConfig.VERSION_CODE / VERSION_NAME are read by the sideload updater.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR pairing: CameraX preview/analysis + ML Kit's bundled barcode model (no Play Services
    // required, so the scanner also works on de-Googled devices).
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
