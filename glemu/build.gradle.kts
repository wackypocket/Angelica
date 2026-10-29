plugins {
    `java-library`
    `maven-publish`
}

version = rootProject.version

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

val rfgObfAttr = Attribute.of("com.gtnewhorizons.retrofuturagradle.obfuscation", String::class.java)
val rfgTransformedAttr = Attribute.of("rfgDeobfuscatorTransformed", Boolean::class.javaObjectType)

configurations {
    listOf("apiElements", "runtimeElements").forEach { name ->
        named(name) {
            attributes {
                attribute(rfgObfAttr, "mcp")
                attribute(rfgTransformedAttr, true)
            }
        }
    }
}

val lwjglNatives: String by extra

repositories {
    mavenLocal()
    maven {
        name = "Mojang"
        url = uri("https://libraries.minecraft.net")
    }
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        name = "Forge"
        url = uri("https://maven.minecraftforge.net/")
    }
    maven {
        name = "taumc"
        url = uri("https://maven.taumc.org/releases")
    }
    mavenCentral()
}

dependencies {
    api(project(":glsm"))

    compileOnly(libs.lwjgl3ify) { artifact { classifier = "dev" }; isTransitive = false }
    compileOnly(libs.retrofuturabootstrap) { isTransitive = false }
    compileOnly(libs.celeritas.common) { isTransitive = false }

    compileOnly(libs.lwjgl3.opengl)
    compileOnly(libs.bundles.lwjgl3.sdlgpu)

    compileOnly(libs.log4j.api)
    compileOnly(libs.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bundles.lwjgl3.sdlgpu)
    libs.bundles.lwjgl3.sdlgpu.get().forEach {
        testRuntimeOnly(it) { artifact { classifier = lwjglNatives } }
    }
    testImplementation(testFixtures(project(":glsm")))
    testImplementation(project(":glsm")) {
        capabilities { requireCapability("${project.group}:glsm-stubs") }
    }
    testImplementation(libs.celeritas.common) { isTransitive = false }
    testImplementation(libs.lwjgl3.opengl)
    testRuntimeOnly(libs.lwjgl3.opengl) { artifact { classifier = lwjglNatives } }
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.log4j.core)
    testRuntimeOnly(libs.fastutil)
    testRuntimeOnly(libs.joml)
    testRuntimeOnly(libs.commons.lang3)
    testRuntimeOnly(libs.guava)
}

tasks.test {
    useJUnitPlatform()
}

val unpackLwjgl3Natives = registerLwjgl3NativesUnpack()
tasks.test.configure { loadLwjgl3NativesFrom(unpackLwjgl3Natives) }

tasks.named<JavaCompile>("compileJava") {
    val classesDir = destinationDirectory
    doLast { injectLwjgl3Aware(classesDir.get().asFile) }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Lwjgl3ify-Aware" to "true"
        )
    }
}
