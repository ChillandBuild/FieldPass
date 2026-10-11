import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val configuredApiBaseUrl = project.findProperty("fieldpassApiBaseUrl") as String?
val verifyReleaseApiBaseUrl = tasks.register("verifyReleaseApiBaseUrl") {
    doLast {
        require(!configuredApiBaseUrl.isNullOrBlank()) {
            "Release builds require -PfieldpassApiBaseUrl=https://your-deployment.example"
        }
        val validHttpsUrl = runCatching {
            val uri = URI(configuredApiBaseUrl.trim())
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
        require(validHttpsUrl) {
            "Release builds require a valid HTTPS fieldpassApiBaseUrl with a host"
        }
    }
}
tasks.configureEach {
    if (name.contains("Release", ignoreCase = true) && name != verifyReleaseApiBaseUrl.name) {
        dependsOn(verifyReleaseApiBaseUrl)
    }
}
// Release signing comes from environment variables or the untracked android/keystore.properties; nothing secret is in git.
// FIELDPASS_KEYSTORE / storeFile, FIELDPASS_KEYSTORE_PASSWORD / storePassword, FIELDPASS_KEY_ALIAS / keyAlias,
// FIELDPASS_KEY_PASSWORD / keyPassword. With none of them set, release stays unsigned.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(propName)?.takeIf { it.isNotBlank() }
val signingValues = mapOf(
    "FIELDPASS_KEYSTORE" to signingValue("FIELDPASS_KEYSTORE", "storeFile"),
    "FIELDPASS_KEYSTORE_PASSWORD" to signingValue("FIELDPASS_KEYSTORE_PASSWORD", "storePassword"),
    "FIELDPASS_KEY_ALIAS" to signingValue("FIELDPASS_KEY_ALIAS", "keyAlias"),
    "FIELDPASS_KEY_PASSWORD" to signingValue("FIELDPASS_KEY_PASSWORD", "keyPassword"),
)
val releaseSigningConfigured = signingValues.values.all { it != null }
if (!releaseSigningConfigured && signingValues.values.any { it != null }) {
    throw GradleException(
        "Release signing is partly configured. Missing: " +
            signingValues.filterValues { it == null }.keys.joinToString(", ")
    )
}
val apiBaseUrl: String = configuredApiBaseUrl ?: "http://192.168.43.10:8000"
val fixtureDefault: String = (project.findProperty("fieldpassFixtureMode") as String?) ?: "false"

android {
    namespace = "com.fieldpass.student"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fieldpass.student"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"$apiBaseUrl\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(signingValues.getValue("FIELDPASS_KEYSTORE")!!)
                storePassword = signingValues.getValue("FIELDPASS_KEYSTORE_PASSWORD")
                keyAlias = signingValues.getValue("FIELDPASS_KEY_ALIAS")
                keyPassword = signingValues.getValue("FIELDPASS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "FIXTURE_AVAILABLE", "true")
            buildConfigField("boolean", "FIXTURE_DEFAULT", fixtureDefault)
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "FIXTURE_AVAILABLE", "false")
            buildConfigField("boolean", "FIXTURE_DEFAULT", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Opt-in backend contract tests (package ...integration) read these; unset = they skip.
            // Only the URL is a declared input, so the password never reaches Gradle's task inputs.
            val apiUrl = System.getenv("FIELDPASS_API_URL")
            test.inputs.property("fieldpassApiUrl", apiUrl ?: "")
            if (apiUrl != null) {
                test.outputs.upToDateWhen { false }
                listOf("FIELDPASS_API_URL", "FIELDPASS_PASSWORD", "FIELDPASS_FACE_IMAGE").forEach { name ->
                    System.getenv(name)?.let { test.environment(name, it) }
                }
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.play.services.location)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
}
