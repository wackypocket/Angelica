import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

val embedOnly: Configuration by configurations
// Only the root build bundles MoltenVK and has the native backends; Umbra applies this script too
val bundledMoltenVK = configurations.findByName("bundledMoltenVK")
val nativeProjects = listOf(":glemu", ":vulkan", ":metal").filter { findProject(it) != null }

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(embedOnly)
    from(embedOnly.map(::zipTree))
    if (bundledMoltenVK != null) {
        dependsOn(bundledMoltenVK)
        from(provider { bundledMoltenVK.map(::zipTree) }) {
            exclude("META-INF/**")
        }
    }

    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

    minimize {
        exclude(project(rootProject.path))
        exclude(project(":glsm"))
        exclude(project(":lwjgl3-backend"))
        exclude(project(":sdl-gpu"))
        nativeProjects.forEach { exclude(project(it)) }
        exclude(dependency("org.taumc:.*:.*"))
        exclude(dependency("org.embeddedt.celeritas:.*:.*"))
        exclude(dependency("org.antlr:.*:.*"))
    }

    relocate("org.taumc.celeritas", "org.taumc.celeritas")
    relocate("org.embeddedt", "org.embeddedt")
    relocate("com.gtnewhorizons.angelica.config", "com.gtnewhorizons.angelica.config")
    relocate("com.gtnewhorizons.angelica.glsm", "com.gtnewhorizons.angelica.glsm")
    relocate("com.gtnewhorizons.angelica.lwjgl3", "com.gtnewhorizons.angelica.lwjgl3")
    relocate("com.gtnewhorizons.angelica.sdlgpu", "com.gtnewhorizons.angelica.sdlgpu")
    relocate("com.gtnewhorizons.angelica.glemu", "com.gtnewhorizons.angelica.glemu")
    relocate("com.gtnewhorizons.angelica.vulkan", "com.gtnewhorizons.angelica.vulkan")
    relocate("com.gtnewhorizons.angelica.metal", "com.gtnewhorizons.angelica.metal")
}
