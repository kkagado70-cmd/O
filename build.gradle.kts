plugins {
    alias(libs.plugins.fabric.loom)
}

val minecraftVersion = libs.versions.minecraft.get()

repositories {
    maven("https://maven.meteordev.org/releases")
    maven("https://maven.meteordev.org/snapshots")
    mavenCentral()
}

dependencies {
    minecraft(libs.minecraft)
    mappings(loom.officialMojangMappings())
    modImplementation(libs.fabric.loader)
    modImplementation(libs.meteor.client)
}

tasks {
    processResources {
        val properties = mapOf(
            "version" to project.version,
            "mc_version" to minecraftVersion
        )

        inputs.properties(properties)

        filesMatching("fabric.mod.json") {
            expand(properties)
        }
    }

    jar {
        from("LICENSE") {
            rename { "${it}_${project.base.archivesName.get()}" }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
}
