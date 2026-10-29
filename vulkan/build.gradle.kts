plugins {
    `java-library`
    `maven-publish`
}

configureNativeBackend(
    NativeBackend(
        artifactId = "vulkan",
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
}

abstract class VendorVulkanClasses : Sync() {
    @get:javax.inject.Inject
    abstract val archives: ArchiveOperations
}

// lwjgl3ify's instance ships no lwjgl-vulkan.
// Drop this once lwjgl3ify ships lwjgl-vulkan.
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
