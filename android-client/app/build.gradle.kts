import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

// Whether the login/session WebViews log each network response they see — see
// WebViewNetworkLogging — decoded (post-gzip, as text) exactly as the page itself received it.
// Read from an env var at build time rather than hardcoded per build type, so a field issue can
// be reproduced on a release-signed build by setting LOG_HTTP_RESPONSES for that one build,
// without shipping an actual debug build (which also turns off minification/obfuscation). Unset
// falls back to the per-build-type default declared alongside each buildConfigField below.
val logHttpResponsesEnv: Boolean? = System.getenv("LOG_HTTP_RESPONSES")
    ?.let { it.equals("true", ignoreCase = true) || it == "1" }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val releaseUpdatePropertiesFile = rootProject.file("release-update.properties")
val releaseUpdateProperties = Properties().apply {
    if (releaseUpdatePropertiesFile.exists()) {
        releaseUpdatePropertiesFile.inputStream().use(::load)
    }
}

val isReleaseTask = gradle.startParameter.taskNames.any {
    it.contains("prepareReleaseUpdate", ignoreCase = true) ||
    it.contains("bumpVersion", ignoreCase = true)
}

val currentCode = (releaseUpdateProperties.getProperty("versionCode", "1")).toInt()
val currentName = releaseUpdateProperties.getProperty("versionName", "1.0.0")

val computedVersionCode = if (isReleaseTask) currentCode + 1 else currentCode
val computedVersionName = if (isReleaseTask) {
    val parts = currentName.split(".")
    if (parts.size == 3) {
        val patch = (parts[2].toIntOrNull() ?: 0) + 1
        "${parts[0]}.${parts[1]}.$patch"
    } else {
        "1.0.$computedVersionCode"
    }
} else {
    currentName
}

if (isReleaseTask) {
    releaseUpdateProperties.setProperty("versionCode", computedVersionCode.toString())
    releaseUpdateProperties.setProperty("versionName", computedVersionName)
    val notesProp = providers.gradleProperty("updateReleaseNotes").orNull
    if (!notesProp.isNullOrBlank()) {
        releaseUpdateProperties.setProperty("releaseNotes", notesProp)
    }
    releaseUpdatePropertiesFile.outputStream().use { releaseUpdateProperties.store(it, "Auto-incremented release version") }
    println("[AUTO-VERSION] Incremented release version: $currentName ($currentCode) -> $computedVersionName ($computedVersionCode)")
}

android {
    namespace = "com.feedpilot.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.feedpilot.client"
        minSdk = 24
        targetSdk = 35
        versionCode = computedVersionCode
        versionName = computedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Launcher label — each build type overrides this so variants are distinguishable
        // when installed side by side (see buildTypes below).
        manifestPlaceholders["appLabel"] = "FeedPilot"

        // Backend base URL — override per build type below.
        buildConfigField("String", "API_BASE_URL", "\"https://feedpilot-api-ount.onrender.com/\"")

        // Note: the X-App-Id that identifies a clone is no longer a build constant. It is read
        // at runtime from the package name (see DeviceIdentity.appId), so a cloner tool that
        // rewrites the package produces a unique id automatically — no per-clone build edit.

        // Instagram OAuth (official Basic Display / Graph API). The user authenticates on
        // Instagram's own page — the app never handles the password. Fill these from your Meta app.
        buildConfigField("String", "INSTAGRAM_CLIENT_ID", "\"YOUR_INSTAGRAM_APP_ID\"")
        buildConfigField(
            "String",
            "INSTAGRAM_REDIRECT_URI",
            "\"https://feedpilot-api-ount.onrender.com/oauth/instagram/callback\""
        )
        buildConfigField("String", "INSTAGRAM_SCOPES", "\"user_profile,user_media\"")

        // Shared with the backend's RequestSigning:Secret (see RequestSigningMiddleware). Signs
        // every API call so the backend can reject traffic that didn't come from this app — a
        // curl/Postman call with a stolen JWT still fails without this. Not a secret in the
        // "never leaves the binary" sense (anyone who decompiles the APK can read it), only in
        // the sense that a request forged from outside the app doesn't have it; treat it the
        // same as the release keystore password already committed here — replace both with your
        // own values for a real deployment, this pair is only for getting the app running.
        buildConfigField("String", "REQUEST_SIGNING_SECRET", "\"1705ed0eea571f7a850efb27d0334772e99f82d7cbc45abb873f858e9d8c79bc\"")
    }

    signingConfigs {
        // Self-signed release key so `assembleRelease` produces an installable APK. Replace
        // with your own keystore for the Play Store; a cloner re-signs anyway. Keep
        // release.keystore out of version control.
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = "feedpilot"
            keyAlias = "feedpilot"
            keyPassword = "feedpilot"
        }
    }

    buildTypes {
        // Distinct applicationId per build type lets debug, staging, and prod (release) coexist
        // on the same device for development. Each also gets its own launcher label.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "FeedPilot Debug"
            signingConfig = signingConfigs.getByName("release")
            // 10.0.2.2 is the stock-emulator alias for the host loopback; it does not route
            // on Genymotion/VirtualBox devices. 192.168.56.1 is the host on the VirtualBox
            // host-only network. Switch back to 10.0.2.2 when using the stock emulator.
            buildConfigField("String", "API_BASE_URL", "\"https://feedpilot-api-ount.onrender.com/\"")
            buildConfigField("boolean", "LOG_HTTP_BODY", (logHttpResponsesEnv ?: true).toString())
        }
        create("staging") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            manifestPlaceholders["appLabel"] = "FeedPilot Staging"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "API_BASE_URL", "\"https://feedpilot-api-ount.onrender.com/\"")
            buildConfigField("boolean", "LOG_HTTP_BODY", (logHttpResponsesEnv ?: true).toString())
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["appLabel"] = "FeedPilot"
            signingConfig = signingConfigs.getByName("release")
            // Production backend on feedpilot-api-ount.onrender.com.
            buildConfigField("String", "API_BASE_URL", "\"https://feedpilot-api-ount.onrender.com/\"")
            // Off by default in release: full request/response bodies can carry session tokens
            // and PII. Set LOG_HTTP_RESPONSES=true for this build only when actively diagnosing a
            // field issue, not left on for a shipped build.
            buildConfigField("boolean", "LOG_HTTP_BODY", (logHttpResponsesEnv ?: false).toString())
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
        }
    }
    testOptions {
        unitTests {
            // Stubbed android.jar methods (android.util.Log etc.) return defaults instead
            // of throwing, so plain JVM tests can exercise the data layer.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.androidx.browser)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    // Vetted X25519 for the Instagram sealed-box password crypto (see InstagramCrypto /
    // NaclSealedBox). Pinned to the version security-crypto already pulls in transitively.
    implementation("com.google.crypto.tink:tink-android:1.8.0")

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // Must precede the mockable android.jar so JSONObject is the real implementation.
    testImplementation(libs.json)
}

val bumpVersion by tasks.registering {
    group = "release"
    description = "Increments release versionCode and versionName in release-update.properties."
    doLast {
        logger.lifecycle("Auto-incremented version ready: versionCode=$computedVersionCode, versionName=$computedVersionName")
    }
}

val updateBaseApkUrl = providers.gradleProperty("updateBaseApkUrl")
    .orElse(releaseUpdateProperties.getProperty("baseApkUrl", "https://feedpilot-api-ount.onrender.com/apk"))
val updateForce = providers.gradleProperty("updateForce")
    .orElse(releaseUpdateProperties.getProperty("forceUpdate", "false"))
val updateReleaseNotes = providers.gradleProperty("updateReleaseNotes")
    .orElse(releaseUpdateProperties.getProperty("releaseNotes", "Bug fixes and stability improvements."))

fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun jsonString(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

fun sqlString(value: String): String = "'" + value.replace("'", "''") + "'"

val prepareReleaseUpdate by tasks.registering {
    group = "release"
    description = "Builds release APK and writes auto-update JSON + SQL metadata."
    notCompatibleWithConfigurationCache("Generates release metadata from the assembled APK and project release config.")
    dependsOn("assembleRelease")

    val apkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val outputDir = layout.buildDirectory.dir("outputs/update/release")
    inputs.file(apkFile)
    outputs.dir(outputDir)

    doLast {
        val apk = apkFile.get().asFile
        require(apk.exists()) {
            "Release APK not found at ${apk.absolutePath}. Run assembleRelease first."
        }

        val versionCode = android.defaultConfig.versionCode
        val versionName = android.defaultConfig.versionName ?: versionCode.toString()
        val publishedApkName = "feedpilot-$versionName-$versionCode.apk"
        val baseUrl = updateBaseApkUrl.get().trimEnd('/')
        val apkUrl = "$baseUrl/$publishedApkName"
        val sha256 = apk.sha256Hex()
        val sizeBytes = apk.length()
        val force = updateForce.get().equals("true", ignoreCase = true)
        val notes = updateReleaseNotes.get()
        val releaseId = UUID.randomUUID().toString()

        val outDir = outputDir.get().asFile
        outDir.mkdirs()
        val publishedApk = File(outDir, publishedApkName)
        apk.copyTo(publishedApk, overwrite = true)

        File(outDir, "update-metadata.json").writeText(
            """
            {
              "versionCode": $versionCode,
              "versionName": "${jsonString(versionName)}",
              "apkUrl": "${jsonString(apkUrl)}",
              "sha256": "$sha256",
              "sizeBytes": $sizeBytes,
              "releaseNotes": "${jsonString(notes)}",
              "forceUpdate": $force
            }
            """.trimIndent() + System.lineSeparator()
        )

        File(outDir, "app-release.sql").writeText(
            """
            UPDATE "AppReleases"
            SET
              "VersionName" = ${sqlString(versionName)},
              "ApkUrl" = ${sqlString(apkUrl)},
              "Sha256" = ${sqlString(sha256)},
              "SizeBytes" = $sizeBytes,
              "ReleaseNotes" = ${sqlString(notes)},
              "ForceUpdate" = ${force.toString().uppercase()}
            WHERE "VersionCode" = $versionCode;

            INSERT INTO "AppReleases" ("Id", "VersionCode", "VersionName", "ApkUrl", "Sha256", "SizeBytes", "ReleaseNotes", "ForceUpdate", "CreatedAt")
            SELECT ${sqlString(releaseId)}, $versionCode, ${sqlString(versionName)}, ${sqlString(apkUrl)}, ${sqlString(sha256)}, $sizeBytes, ${sqlString(notes)}, ${force.toString().uppercase()}, now()
            WHERE NOT EXISTS (
              SELECT 1 FROM "AppReleases" WHERE "VersionCode" = $versionCode
            );
            """.trimIndent() + System.lineSeparator()
        )

        logger.lifecycle("Auto-update package ready: ${outDir.absolutePath}")
        logger.lifecycle("Upload APK: ${publishedApk.absolutePath}")
        logger.lifecycle("Backend metadata SQL: ${File(outDir, "app-release.sql").absolutePath}")
    }
}
