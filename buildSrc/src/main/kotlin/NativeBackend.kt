import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Attribute
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.*
import java.time.Duration

class NativePlatform(val isHost: Boolean, val requirement: String)

private const val NATIVE_DEVICE_TAG = "native-device"
private const val NATIVE_GLSM_TAG = "glsm-native"

class NativeBackend(
    val deviceTag: String,
    val glsmTag: String,
    val deviceTask: String,
    val glsmTask: String,
    val stubsSourceSet: String,
    val deviceDescription: String,
    val glsmDescription: String,
    val gateProperty: String,
    val lwjglServiceClass: String,
    val platform: NativePlatform? = null,
    val testJvmArgs: List<String> = emptyList(),
    val testSystemProperties: Map<String, String> = emptyMap(),
)

private fun VersionCatalog.lib(alias: String): MinimalExternalModuleDependency = findLibrary(alias).get().get()

private fun DependencyHandler.addExternal(configuration: String, dependency: MinimalExternalModuleDependency, configure: ExternalModuleDependency.() -> Unit = {}) {
    val created = create(dependency) as ExternalModuleDependency
    created.configure()
    add(configuration, created)
}

/** The test classpath of a rig that runs against LWJGL 3 rather than the game's LWJGL 2. */
fun FileCollection.withoutLwjgl2(): FileCollection = filter { file ->
    !file.name.startsWith("lwjgl-2.") && !file.name.startsWith("lwjgl_util-") && !file.name.startsWith("lwjgl-platform-")
}

/** What the Java 25 rendering modules have in common before their own dependencies: the toolchain, RFG attributes and repositories. */
fun Project.configureGpuModuleBasics() {
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }

    val rfgObfAttr = Attribute.of("com.gtnewhorizons.retrofuturagradle.obfuscation", String::class.java)
    val rfgTransformedAttr = Attribute.of("rfgDeobfuscatorTransformed", Boolean::class.javaObjectType)
    listOf("apiElements", "runtimeElements").forEach { name ->
        configurations.named(name) {
            attributes {
                attribute(rfgObfAttr, "mcp")
                attribute(rfgTransformedAttr, true)
            }
        }
    }

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
}

fun Project.addGpuTestDependencies() {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val lwjglNatives = extensions.extraProperties["lwjglNatives"] as String
    val projectGroup = group.toString()
    dependencies {
        add("testImplementation", platform(libs.lib("junit.bom")))
        addExternal("testImplementation", libs.lib("junit.jupiter"))
        add("testImplementation", testFixtures(project(":glsm")))
        add("testImplementation", project(":glsm").apply {
            capabilities { requireCapability("$projectGroup:glsm-stubs") }
        })
        addExternal("testImplementation", libs.lib("celeritas.common")) { isTransitive = false }
        addExternal("testImplementation", libs.lib("lwjgl3.opengl"))
        // GL.<clinit> loads liblwjgl_opengl. Test scope only: a root-level runtimeOnly collides with lwjgl3Bindings
        addExternal("testRuntimeOnly", libs.lib("lwjgl3.opengl")) { artifact { classifier = lwjglNatives } }
        addExternal("testRuntimeOnly", libs.lib("junit.platform.launcher"))
        addExternal("testRuntimeOnly", libs.lib("log4j.core"))
        addExternal("testRuntimeOnly", libs.lib("fastutil"))
        addExternal("testRuntimeOnly", libs.lib("joml"))
        addExternal("testRuntimeOnly", libs.lib("commons.lang3"))
        addExternal("testRuntimeOnly", libs.lib("guava"))
        addExternal("testImplementation", libs.lib("asm.tree.test"))
    }
}

/** What a task running against a real device needs: never cached, bounded, and able to show where it hung. */
fun Test.configureGpuTestTask() {
    outputs.cacheIf { false }
    outputs.upToDateWhen { false }
    // CTS and conformance-probe runs take far longer than a test run.
    timeout.set(Duration.ofMinutes(if (System.getenv("ANGELICA_CTS_LIBRARY") != null) 240 else if (System.getenv("ANGELICA_CONFORMANCE_PROBE") != null) 60 else 5))
    systemProperty("junit.jupiter.execution.timeout.default", "60s")
    systemProperty("junit.jupiter.execution.timeout.threaddump.enabled", "true")
    systemProperty("angelica.requireDevice", project.findProperty("angelica.requireDevice") ?: "false")
}

fun Project.configureNativeBackend(backend: NativeBackend) {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val lwjglNatives = extensions.extraProperties["lwjglNatives"] as String

    version = rootProject.version

    configureGpuModuleBasics()
    addGpuTestDependencies()

    val glsmRedirectAgent = configurations.create("glsmRedirectAgent") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }

    dependencies {
        add("api", project(":glsm"))
        add("api", project(":glemu"))

        addExternal("compileOnly", libs.lib("lwjgl3ify")) {
            artifact { classifier = "dev" }
            isTransitive = false
        }
        addExternal("compileOnly", libs.lib("retrofuturabootstrap")) { isTransitive = false }
        addExternal("compileOnly", libs.lib("celeritas.common")) { isTransitive = false }
        addExternal("compileOnly", libs.lib("lwjgl3.opengl"))

        libs.findBundle("lwjgl3.sdlgpu").get().get().forEach { lib ->
            addExternal("implementation", lib)
            addExternal("runtimeOnly", lib) { artifact { classifier = lwjglNatives } }
        }

        addExternal("compileOnly", libs.lib("log4j.api"))
        addExternal("compileOnly", libs.lib("annotations"))

        add(glsmRedirectAgent.name, project(mapOf("path" to ":glsm", "configuration" to "redirectAgent")))
    }

    val sourceSets = extensions.getByType<SourceSetContainer>()
    sourceSets["test"].java.srcDir(rootProject.file("glemu/src/nativeTest/java"))
    val stubs: SourceSet = sourceSets.create(backend.stubsSourceSet) {
        java.srcDir(rootProject.file("glemu/src/glsmNativeStubs/java"))
    }

    val hostSupported = backend.platform?.isHost ?: true

    tasks.withType<Test>().configureEach {
        if (backend.testJvmArgs.isNotEmpty()) jvmArgs(backend.testJvmArgs)
        backend.testSystemProperties.forEach { (k, v) -> systemProperty(k, System.getProperty(k) ?: v) }
    }

    tasks.named<Test>("test") {
        useJUnitPlatform {
            excludeTags(backend.deviceTag, backend.glsmTag, NATIVE_DEVICE_TAG, NATIVE_GLSM_TAG)
        }
    }

    val deviceTest = tasks.register<Test>(backend.deviceTask) {
        description = backend.deviceDescription
        group = "verification"
        backend.platform?.let { p -> onlyIf(p.requirement) { hostSupported } }
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags(backend.deviceTag, NATIVE_DEVICE_TAG)
        }
        configureGpuTestTask()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
            exceptionFormat = TestExceptionFormat.FULL
        }
        mustRunAfter(tasks.named("test"))
    }

    val glsmTest = tasks.register<Test>(backend.glsmTask) {
        description = backend.glsmDescription
        group = "verification"
        backend.platform?.let { p -> onlyIf(p.requirement) { hostSupported } }
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = stubs.output.classesDirs.plus(
            sourceSets["test"].runtimeClasspath.withoutLwjgl2()
        )
        useJUnitPlatform {
            includeTags(backend.glsmTag, NATIVE_GLSM_TAG)
        }
        dependsOn(tasks.named(stubs.classesTaskName))
        javaAgent(glsmRedirectAgent)
        jvmArgs(
            "-D${backend.gateProperty}=true",
            "-Dceleritas.lwjglService=${backend.lwjglServiceClass}",
        )
        configureGpuTestTask()
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
        mustRunAfter(tasks.named("test"), deviceTest)
    }

    if (!providers.environmentVariable("CI").isPresent) {
        tasks.named("check") { dependsOn(deviceTest, glsmTest) }
    }

    val unpackLwjgl3Natives = registerLwjgl3NativesUnpack()
    listOf(deviceTest, glsmTest, tasks.named<Test>("test")).forEach { it.configure { loadLwjgl3NativesFrom(unpackLwjgl3Natives) } }

    tasks.named<JavaCompile>("compileJava") {
        val classesDir = destinationDirectory
        doLast { injectLwjgl3Aware(classesDir.get().asFile) }
    }

    tasks.named<Jar>("jar") {
        manifest {
            attributes("Lwjgl3ify-Aware" to "true")
        }
    }
}
