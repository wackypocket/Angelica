plugins {
    `java-library`
    `maven-publish`
}

val isMac = System.getProperty("os.name").startsWith("Mac")

configureNativeBackend(
    NativeBackend(
        artifactId = "metal",
        deviceTag = "metal-device",
        glsmTag = "glsm-metal",
        deviceTask = "metalDeviceTest",
        glsmTask = "glsmMetalTest",
        stubsSourceSet = "glsmMetalStubs",
        deviceDescription = "Runs the tests that need a real Metal device.",
        glsmDescription = "Runs the headless GLSM-on-Metal rig in its own JVM, with the Metal gate engaged.",
        gateProperty = "angelica.metal",
        lwjglServiceClass = "com.gtnewhorizons.angelica.metal.MetalLWJGLService",
        platform = NativePlatform(isMac, "Metal exists only on macOS"),
        // Metal is reached through java.lang.foreign; without --enable-native-access every downcall logs a restricted-method warning
        testJvmArgs = (if (isMac) listOf("-XstartOnFirstThread") else emptyList()) + "--enable-native-access=ALL-UNNAMED",
    )
)
