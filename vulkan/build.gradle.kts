plugins {
    `java-library`
}

configureNativeBackend(
    NativeBackend(
        deviceTag = "vulkan-device",
        glsmTag = "glsm-vulkan",
        deviceTask = "vulkanDeviceTest",
        glsmTask = "glsmVulkanTest",
        stubsSourceSet = "glsmVulkanStubs",
        deviceDescription = "Runs the tests that need a real Vulkan device and a display.",
        glsmDescription = "Runs the headless GLSM-on-Vulkan rig in its own JVM, with the Vulkan gate engaged.",
        gateProperty = "angelica.vulkan",
        lwjglServiceClass = "com.gtnewhorizons.angelica.vulkan.VulkanLWJGLService",
        testJvmArgs = if (System.getProperty("os.name").startsWith("Mac")) listOf("-XstartOnFirstThread") else emptyList(),
        // CI's only Vulkan device is Mesa's lavapipe.
        testSystemProperties = mapOf("angelica.vulkan.allowSoftware" to "true"),
    )
)

val vendoredVulkan: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    compileOnly(libs.lwjgl3.vulkan)
    vendoredVulkan(libs.lwjgl3.vulkan)
    testImplementation(libs.lwjgl3.vulkan)
    if (System.getProperty("os.name").startsWith("Mac")) testRuntimeOnly(project(":moltenvk"))
}

// Keeps the driver's answers apart from the suites: it needs a real OpenGL context and none of our redirects.
val glOracleTest = tasks.register<Test>("glOracleTest") {
    description = "Records what the machine's OpenGL driver answers, for glemu/scripts/compare_conformance.py."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath.withoutLwjgl2()
    // Natives from the unpacked directory, so LWJGL does not extract them to the shared temp dir.
    loadLwjgl3NativesFrom(tasks.named<UnpackLwjgl3Natives>("unpackLwjgl3Natives"))
    useJUnitPlatform { includeTags("gl-oracle") }
    configureGpuTestTask()
    // NVIDIA's threaded optimisation, which its driver turns on by itself, crashes on its own thread partway through the probes.
    environment("__GL_THREADED_OPTIMIZATIONS", "0")
}

tasks.named<Test>("test") { options { (this as org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions).excludeTags("gl-oracle") } }

abstract class VendorVulkanClasses : Sync() {
    @get:javax.inject.Inject
    abstract val archives: ArchiveOperations
}

// lwjgl3ify's instance ships no lwjgl-vulkan; drop this once it does.
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
