plugins {
    `java-library`
    `maven-publish`
}

configureNativeBackend(
    NativeBackend(
        artifactId = "directx12",
        deviceTag = "dx12-device",
        glsmTag = "glsm-dx12",
        deviceTask = "dx12DeviceTest",
        glsmTask = "glsmDx12Test",
        stubsSourceSet = "glsmDx12Stubs",
        deviceDescription = "Runs the tests that need a real Direct3D 12 device.",
        glsmDescription = "Runs the headless GLSM-on-D3D12 rig in its own JVM, with the D3D12 gate engaged.",
        gateProperty = "angelica.dx12",
        lwjglServiceClass = "com.gtnewhorizons.angelica.dx12.Dx12LWJGLService",
        platform = NativePlatform(System.getProperty("os.name").startsWith("Windows"), "Direct3D 12 exists only on Windows"),
        testJvmArgs = listOf("--enable-native-access=ALL-UNNAMED"),
        testSystemProperties = mapOf("angelica.dx12.debug" to "true"),
    )
)
