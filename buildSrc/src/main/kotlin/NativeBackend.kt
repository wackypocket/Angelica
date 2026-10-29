import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Attribute
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
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

class NativeBackend(
    val artifactId: String,
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

fun Project.configureNativeBackend(backend: NativeBackend) {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val lwjglNatives = extensions.extraProperties["lwjglNatives"] as String
    val projectGroup = group.toString()

    version = rootProject.version

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

        add("testImplementation", platform(libs.lib("junit.bom")))
        addExternal("testImplementation", libs.lib("junit.jupiter"))
        add("testImplementation", testFixtures(project(":glsm")))
        add("testImplementation", project(":glsm").apply {
            capabilities { requireCapability("$projectGroup:glsm-stubs") }
        })
        addExternal("testImplementation", libs.lib("celeritas.common")) { isTransitive = false }
        addExternal("testImplementation", libs.lib("lwjgl3.opengl"))
        addExternal("testRuntimeOnly", libs.lib("lwjgl3.opengl")) { artifact { classifier = lwjglNatives } }
        addExternal("testRuntimeOnly", libs.lib("junit.platform.launcher"))
        addExternal("testRuntimeOnly", libs.lib("log4j.core"))
        addExternal("testRuntimeOnly", libs.lib("fastutil"))
        addExternal("testRuntimeOnly", libs.lib("joml"))
        addExternal("testRuntimeOnly", libs.lib("commons.lang3"))
        addExternal("testRuntimeOnly", libs.lib("guava"))
        addExternal("testImplementation", libs.lib("asm.tree.test"))

        add(glsmRedirectAgent.name, project(mapOf("path" to ":glsm", "configuration" to "redirectAgent")))
    }

    val sourceSets = extensions.getByType<SourceSetContainer>()
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
            excludeTags(backend.deviceTag, backend.glsmTag)
        }
    }

    val deviceTest = tasks.register<Test>(backend.deviceTask) {
        description = backend.deviceDescription
        group = "verification"
        backend.platform?.let { p -> onlyIf(p.requirement) { hostSupported } }
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags(backend.deviceTag)
        }
        outputs.cacheIf { false }
        outputs.upToDateWhen { false }
        timeout.set(Duration.ofMinutes(5))
        systemProperty("junit.jupiter.execution.timeout.default", "60s")
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
            sourceSets["test"].runtimeClasspath.filter { file ->
                !file.name.startsWith("lwjgl-2.") && !file.name.startsWith("lwjgl_util-") && !file.name.startsWith("lwjgl-platform-")
            }
        )
        useJUnitPlatform {
            includeTags(backend.glsmTag)
        }
        dependsOn(tasks.named(stubs.classesTaskName))
        javaAgent(glsmRedirectAgent)
        jvmArgs(
            "-D${backend.gateProperty}=true",
            "-Dceleritas.lwjglService=${backend.lwjglServiceClass}",
        )
        outputs.cacheIf { false }
        outputs.upToDateWhen { false }
        timeout.set(Duration.ofMinutes(5))
        systemProperty("junit.jupiter.execution.timeout.default", "60s")
        systemProperty("junit.jupiter.execution.timeout.threaddump.enabled", "true")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
        mustRunAfter(tasks.named("test"), deviceTest)
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

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                groupId = "com.gtnewhorizons.angelica"
                artifactId = backend.artifactId
                version = rootProject.version.toString()
                from(components["java"])
            }
        }
    }
}
