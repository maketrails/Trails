rootProject.name = "Trails"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("org.chromium")
            }
        }
        mavenCentral()
        maven("https://api.mapbox.com/downloads/v2/releases/maven") {
            credentials {
                // Set in ~/.gradle/gradle.properties: MAPBOX_DOWNLOADS_TOKEN=your_token
                username = "mapbox"
                password = providers.gradleProperty("mapbox.token").orElse("").get()
            }
        }
        maven {
            name = "GitHub Authentikt"
            url = uri("https://maven.pkg.github.com/Julius-Babies/authentikt")
            credentials {
                username = providers.gradleProperty("maven.pkg.github.com.user").orElse("").get()
                password = providers.gradleProperty("maven.pkg.github.com.token").orElse("").get()
            }
        }
        maven {
            // Public, no credentials needed: commonapi and its plus.vplan.lib dependencies
            name = "GitLab VPlanPlus"
            url = uri("https://gitlab.jvbabi.es/api/v4/groups/12/-/packages/maven")
            mavenContent {
                includeGroupAndSubgroups("plus.vplan")
            }
        }
    }
}

include(":app:android")
include(":app:shared")
include(":shared")
include(":server")