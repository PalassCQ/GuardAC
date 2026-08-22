plugins {
    kotlin("jvm") version "1.9.23"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.guardac"
version = "1.9"

fun git(vararg args: String): String = try {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    if (process.exitValue() == 0 && text.isNotEmpty()) text else "unknown"
} catch (e: Exception) {
    "unknown"
}

val gitHash: String = git("rev-parse", "--short", "HEAD")
val buildDate: String = git("log", "-1", "--format=%cd", "--date=format:%Y-%m-%d")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
    implementation("com.github.retrooper:packetevents-spigot:2.13.0")
    implementation(kotlin("stdlib"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.9")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.2.15")
    compileOnly("me.clip:placeholderapi:2.11.6")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks {
    processResources {
        inputs.property("version", project.version.toString())
        inputs.property("gitHash", gitHash)
        inputs.property("buildDate", buildDate)
        filesMatching(listOf("plugin.yml", "build-info.properties")) {
            expand(
                "version"   to project.version,
                "gitHash"   to gitHash,
                "buildDate" to buildDate,
            )
        }
    }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        relocate("com.fasterxml.jackson", "dev.guardac.libs.jackson")
        relocate("org.jetbrains.kotlin", "dev.guardac.libs.kotlin")
        relocate("com.github.retrooper.packetevents", "dev.guardac.libs.packetevents.api")
        relocate("io.github.retrooper.packetevents", "dev.guardac.libs.packetevents.impl")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    build {
        dependsOn(shadowJar)
    }
}
