pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version ("2.0.27")
}

apply(from = file("gradle/lwjgl-natives.settings.gradle.kts"))
apply(from = file("experimental-surround/surround.settings.gradle.kts"))

include("glsm")
include("glemu")
include("lwjgl3-backend")
include("tracy-client")
include("moltenvk")
include("dxc")
include("sdl-gpu")
include("vulkan")
include("metal")
include("directx12")
