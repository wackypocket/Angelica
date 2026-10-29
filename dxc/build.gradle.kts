import java.net.URI
import java.security.MessageDigest

plugins {
    `java-library`
    `maven-publish`
}

version = rootProject.version

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
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

val dxcRelease = libs.versions.dxc.get()
val dxcVersion = (findProperty("dxcVersion") as String?) ?: dxcRelease

val dxcArchiveName = "dxc_2026_09_29.zip"
val dxcArchiveUrl = "https://github.com/microsoft/DirectXShaderCompiler/releases/download/v$dxcRelease/$dxcArchiveName"
val dxcArchiveSha256 = "ad31b1fc8443175d204f77a611fdb3ef2ec42759bdc2f1167368de24a4a7e7f1"

repositories {
    maven {
        name = "Forge"
        url = uri("https://maven.minecraftforge.net/")
    }
    maven {
        name = "Forge artifact-only"
        url = uri("https://maven.minecraftforge.net/")
        metadataSources { artifact() }
        content { includeModule("net.minecraftforge", "forge") }
    }
    mavenCentral()
}

dependencies {
    compileOnly("net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10:universal") { isTransitive = false }
}

val fetchDxc = tasks.register("fetchDxc") {
    val local = (findProperty("dxcArchive") as String?)?.let { file(it) }
    val archive = layout.buildDirectory.file("dxc/$dxcArchiveName")
    val url = dxcArchiveUrl
    val expected = dxcArchiveSha256
    val name = dxcArchiveName
    inputs.property("sha256", expected)
    if (local != null) inputs.file(local)
    outputs.file(archive)
    doLast {
        val out = archive.get().asFile
        out.parentFile.mkdirs()
        if (local != null) {
            local.copyTo(out, overwrite = true)
        } else {
            URI(url).toURL().openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(out.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            out.delete()
            throw GradleException("$name has SHA-256 $actual, expected $expected")
        }
    }
}

val extractDxc = tasks.register<Sync>("extractDxc") {
    dependsOn(fetchDxc)
    val outDir = layout.buildDirectory.dir("generated/resources/dxc")
    val wanted = setOf("bin/x64/dxcompiler.dll", "bin/arm64/dxcompiler.dll", "LICENSE-LLVM.txt", "LICENSE-MS.txt")
    from(provider { zipTree(fetchDxc.get().outputs.files.singleFile) }) {
        include { it.relativePath.pathString.replace('\\', '/') in wanted }
        eachFile {
            val normalized = relativePath.pathString.replace('\\', '/')
            path = if (normalized.startsWith("bin/")) "windows/" + normalized.removePrefix("bin/") else "META-INF/DXC-$normalized"
        }
    }
    includeEmptyDirs = false
    into(outDir)
    doLast {
        listOf("windows/x64/dxcompiler.dll", "windows/arm64/dxcompiler.dll", "META-INF/DXC-LICENSE-LLVM.txt", "META-INF/DXC-LICENSE-MS.txt").forEach { rel ->
            if (!outDir.get().file(rel).asFile.isFile) throw GradleException("DXC file missing after extraction: $rel")
        }
    }
}
sourceSets["main"].resources.srcDir(extractDxc)

val distJar = tasks.register<Jar>("distJar") {
    archiveBaseName.set("angelica-dxc")
    archiveVersion.set(dxcVersion)
    from(sourceSets["main"].output)
    manifest {
        attributes(
            "FMLCorePlugin" to "com.gtnewhorizons.angelica.dxc.loading.DxcCorePlugin"
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "com.gtnewhorizons.angelica"
            artifactId = "angelica-dxc"
            version = dxcVersion
            artifact(distJar)
        }
    }
    repositories {
        if (hasProperty("publishDxc") && System.getenv("MAVEN_USER") != null) {
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
