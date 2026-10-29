plugins {
    `java-library`
    `maven-publish`
}

configureNativeBackend(
    NativeBackend(
        artifactId = "direct3d12",
        deviceTag = "d3d12-device",
        glsmTag = "glsm-d3d12",
        deviceTask = "d3d12DeviceTest",
        glsmTask = "glsmD3d12Test",
        stubsSourceSet = "glsmD3d12Stubs",
        deviceDescription = "Runs the tests that need a real Direct3D 12 device.",
        glsmDescription = "Runs the headless GLSM-on-D3D12 rig in its own JVM, with the D3D12 gate engaged.",
        gateProperty = "angelica.d3d12",
        lwjglServiceClass = "com.gtnewhorizons.angelica.d3d12.D3d12LWJGLService",
        platform = NativePlatform(System.getProperty("os.name").startsWith("Windows"), "Direct3D 12 exists only on Windows"),
        testJvmArgs = listOf("--enable-native-access=ALL-UNNAMED"),
        testSystemProperties = mapOf("angelica.d3d12.debug" to "true"),
    )
)
