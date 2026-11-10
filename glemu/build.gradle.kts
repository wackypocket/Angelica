plugins {
    `java-library`
    `maven-publish`
}

version = rootProject.version

configureGpuModuleBasics()
addGpuTestDependencies()

val lwjglNatives: String by extra

dependencies {
    api(project(":glsm"))
    api(project(":lwjgl3-backend"))

    compileOnly(libs.lwjgl3ify) { artifact { classifier = "dev" }; isTransitive = false }
    compileOnly(libs.retrofuturabootstrap) { isTransitive = false }
    compileOnly(libs.celeritas.common) { isTransitive = false }

    compileOnly(libs.lwjgl3.opengl)
    compileOnly(libs.bundles.lwjgl3.sdlgpu)

    compileOnly(libs.log4j.api)
    compileOnly(libs.asm.tree.compile)
    compileOnly(libs.annotations)

    testImplementation(libs.lwjgl3ify) { artifact { classifier = "dev" }; isTransitive = false }
    testImplementation(libs.bundles.lwjgl3.sdlgpu)
    libs.bundles.lwjgl3.sdlgpu.get().forEach {
        testRuntimeOnly(it) { artifact { classifier = lwjglNatives } }
    }
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.gtnewhorizons.angelica"
            artifactId = "glemu"
            version = rootProject.version.toString()
            from(components["java"])
        }
    }
    repositories {
        if (System.getenv("MAVEN_USER") != null) {
            maven {
                name = "GTNHMaven"
                url = uri(rootProject.findProperty("mavenPublishUrl")?.toString() ?: "https://nexus.gtnewhorizons.com/repository/releases/")
                credentials {
                    username = System.getenv("MAVEN_USER")
                    password = System.getenv("MAVEN_PASSWORD")
                }
            }
        }
    }
}
