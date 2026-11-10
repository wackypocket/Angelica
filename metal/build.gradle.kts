plugins {
    `java-library`
}

val isMac = System.getProperty("os.name").startsWith("Mac")

configureNativeBackend(
    NativeBackend(
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
        // Metal is reached through LWJGL's JNI bindings, which are restricted native methods
        testJvmArgs = (if (isMac) listOf("-XstartOnFirstThread") else emptyList()) + "--enable-native-access=ALL-UNNAMED",
    )
)
