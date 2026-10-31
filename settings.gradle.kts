pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie" }
    }
}

plugins {
    // For some reason, this plugin is crucial - do not remove
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9+"
}

include(":core")

fun getProperty(key: String): String? {
    return settings.extra[key] as? String
}

val coreModules = getProperty("core_modules")!!.split(',').map { it.trim() }

coreModules.forEach { module ->
    include(":loader-$module")

    val project = project(":loader-$module")
    val dir = module.replace("-", "/")
    project.projectDir = file("loader/$dir")
    when (module) {
        "core" -> project.buildFileName = "../loader-core.gradle.kts"
        "fabric-core" -> project.buildFileName = "../../loader-fabric-core.gradle.kts"
        "fabric-15", "fabric-16" -> project.buildFileName = "../../loader-fabric.gradle.kts"
        "forge-fml40", "forge-fml47" -> project.buildFileName = "../../loader-forge.gradle.kts"
        "neoforge-fml2", "neoforge-fml4", "neoforge-fml10" -> project.buildFileName = "../../loader-neoforge.gradle.kts"
    }
}

stonecutter {
    create(rootProject) {
        fun match(version: String, vararg loaders: String) = loaders
            .forEach { version("$version-$it", version).buildscript = "build.$it.gradle.kts" }

        // This OSS fork intentionally supports only the reviewed Forge 1.20.1 target.
        match("1.20.1", "forge")
    }
}

rootProject.name = "AutoModpack"
