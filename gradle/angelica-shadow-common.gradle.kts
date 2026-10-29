import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

val embedOnly: Configuration by configurations

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(embedOnly)
    from(embedOnly.map(::zipTree))

    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()

    minimize {
        exclude(project(rootProject.path))
        exclude(project(":glsm"))
        exclude(project(":lwjgl3-backend"))
        exclude(project(":sdl-gpu"))
        exclude(project(":glemu"))
        exclude(project(":vulkan"))
        exclude(project(":metal"))
        exclude(project(":directx12"))
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
    relocate("com.gtnewhorizons.angelica.dx12", "com.gtnewhorizons.angelica.dx12")
}
