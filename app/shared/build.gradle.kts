import com.codingfeline.buildkonfig.compiler.FieldSpec.Type
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.build.konfig)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        namespace = "es.jvbabi.trails.shared.compose"

        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        androidResources {
            enable = true
        }
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.app.androidx.browser)
            val mapboxVersion = libs.versions.app.mapbox.get()
            api("com.mapbox.maps:android-ndk27:$mapboxVersion") {
                exclude(group = "com.google.android.gms", module = "play-services-cronet")
            }
            implementation(libs.app.mapbox.compose)
            implementation(libs.app.ktor.client.cio)
        }

        commonMain.dependencies {
            implementation(projects.shared)

            implementation(libs.app.compose.runtime)
            implementation(libs.app.compose.foundation)
            implementation(libs.app.compose.material3)
            implementation(libs.app.compose.ui)
            implementation(libs.app.compose.components.resources)
            implementation(libs.app.compose.uiToolingPreview)
            implementation(libs.app.androidx.lifecycle.viewmodelCompose)
            implementation(libs.app.androidx.lifecycle.runtimeCompose)

            implementation(libs.app.navigation3.runtime)
            implementation(libs.app.navigation3.ui)
            implementation(libs.app.navigation3.lifecycle)

            api(libs.app.koin.compose)
            implementation(libs.app.koin.compose.navigation3)

            implementation(libs.app.kotlinx.datetime)

            implementation(libs.app.androidx.room.runtime)
            implementation(libs.app.androidx.sqlite.bundled)

            api(libs.app.moko.permissions.core)
            api(libs.app.moko.permissions.compose)
            implementation(libs.app.moko.permissions.location)
            implementation(libs.app.moko.permissions.notifications)

            api(libs.app.kermit)

            implementation(libs.app.haze.blur)
            implementation(libs.app.haze.blur.materials)

            implementation(libs.app.human.readable)
            implementation(libs.app.jetlime)
            implementation(libs.app.phosphor.icons.regular)

            api(libs.app.ktor.client.core)
            implementation(libs.app.ktor.client.content.negotiation)
            implementation(libs.app.ktor.client.websockets)
            implementation(libs.app.ktor.serialization.kotlinx.json)
        }

        iosMain.dependencies {
            implementation(libs.app.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.app.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.app.androidx.room.compiler)
    add("kspIosArm64", libs.app.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    androidRuntimeClasspath(libs.app.compose.uiTooling)
}

buildkonfig {
    packageName = "es.jvbabi.trails"

    defaultConfigs {
        buildConfigField(
            type = Type.STRING,
            name =  "WERKBANK_TOKEN",
            value = localProperties["werkbank.access_token"]?.toString(),
            nullable = true,
        )
        // Defined in the root build script, shared with versionName in :app:android.
        buildConfigField(
            type = Type.STRING,
            name = "CURRENT_VERSION",
            value = rootProject.extra["buildTag"] as String,
            nullable = false,
            const = true,
        )
        // Debug builds carry a throwaway version, so checking them against the latest release
        // only ever nags. Opt in per developer machine to work on the update flow itself.
        buildConfigField(
            type = Type.BOOLEAN,
            name = "CHECK_FOR_UPDATES_IN_DEBUG",
            value = localProperties["app.check_for_updates.enable_in_debug"]
                ?.toString()
                .toBoolean()
                .toString(),
            nullable = false,
            const = true,
        )
        // Swaps the updater's repositories for fake ones, so the whole flow can be walked through
        // without a release to update to — and without spending the 60 requests an hour GitHub
        // allows an unauthenticated client. Opt in per developer machine.
        buildConfigField(
            type = Type.BOOLEAN,
            name = "FAKE_UPDATE",
            value = localProperties["app.dev.fake-update"]
                ?.toString()
                .toBoolean()
                .toString(),
            nullable = false,
            const = true,
        )
    }
}