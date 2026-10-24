import java.time.Duration

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

// The game's LWJGL ships no lwjgl-vulkan, so its classes travel inside this module (stamped Lwjgl3Aware, relocated by the root shadowJar).
val vendoredVulkan: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    api(project(":glsm"))

    compileOnly(libs.lwjgl3ify) { artifact { classifier = "dev" }; isTransitive = false }
    compileOnly(libs.retrofuturabootstrap) { isTransitive = false }
    compileOnly(libs.celeritas.common) { isTransitive = false }

    // GL enum constants (compileOnly -- no runtime GL dependency)
    compileOnly(libs.lwjgl3.opengl)

    // LWJGL3 core + SDL3 + SPIRV-Cross + shaderc; Vulkan bindings are vendored below
    implementation(libs.bundles.lwjgl3.sdlgpu)
    libs.bundles.lwjgl3.sdlgpu.get().forEach {
        runtimeOnly(it) { artifact { classifier = lwjglNatives } }
    }
    compileOnly(libs.lwjgl3.vulkan)
    vendoredVulkan(libs.lwjgl3.vulkan)

    compileOnly(libs.log4j.api)
    compileOnly(libs.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.lwjgl3.vulkan)
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

abstract class VendorVulkanClasses : Sync() {
    @get:javax.inject.Inject
    abstract val archives: ArchiveOperations
}

val vendoredVulkanDir = layout.buildDirectory.dir("vendored-vulkan")

val vendorVulkanClasses by tasks.registering(VendorVulkanClasses::class) {
    val archives = archives
    from(vendoredVulkan.elements.map { files -> files.map { archives.zipTree(it) } })
    include("org/lwjgl/vulkan/**")
    exclude("**/module-info.class")
    into(vendoredVulkanDir)
    val outDir = vendoredVulkanDir
    doLast { injectLwjgl3Aware(outDir.get().asFile) }
}

sourceSets.main {
    output.dir(mapOf("builtBy" to vendorVulkanClasses), vendoredVulkanDir)
}

val vulkanDeviceTag = "vulkan-device"

tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").startsWith("Mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags(vulkanDeviceTag)
    }
}

val vulkanDeviceTest by tasks.registering(Test::class) {
    description = "Runs the tests that need a real Vulkan device and a display."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags(vulkanDeviceTag)
    }
    outputs.cacheIf { false }
    outputs.upToDateWhen { false }
    timeout = Duration.ofMinutes(5)
    systemProperty("junit.jupiter.execution.timeout.default", "60s")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    mustRunAfter(tasks.test)
}

val unpackLwjgl3Natives = registerLwjgl3NativesUnpack()
listOf(vulkanDeviceTest, tasks.test).forEach { it.configure { loadLwjgl3NativesFrom(unpackLwjgl3Natives) } }

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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.gtnewhorizons.angelica"
            artifactId = "vulkan"
            version = rootProject.version.toString()
            from(components["java"])
        }
    }
}
