plugins {
    kotlin("jvm") version "1.9.23"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.guardac"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
    // PacketEvents — compileOnly, загружается сервером как отдельный плагин
    // НЕ relocate, чтобы избежать конфликта classloader/Adventure
    compileOnly("com.github.retrooper.packetevents:spigot:2.3.0")
    implementation(kotlin("stdlib"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    // Драйвер SQLite для статистики (DailyStats). Без него jdbc:sqlite не подхватывается
    // и БД статистики молча не создаётся. Содержит нативную либу — НЕ relocate'им.
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    // WorldGuard (region bypass) — compileOnly, плагин-зависимость на сервере (softdepend).
    // Тянет WorldEdit транзитивно для BukkitAdapter. На сервере НЕ требуется, если фича выключена.
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.9")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.2.15") // для BukkitAdapter
}

// Компилируем на установленном JDK (21+), но целимся в байткод Java 17, чтобы jar
// работал на серверах с Java 17+. (jvmToolchain(17) убран: не у всех стоит именно
// JDK 17, а так сборка идёт на любом современном JDK.) Java и Kotlin цели должны
// совпадать — иначе Gradle ругается на несоответствие.
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
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        // PacketEvents убран из relocate — он теперь compileOnly
        relocate("com.fasterxml.jackson", "dev.guardac.libs.jackson")
        relocate("org.jetbrains.kotlin", "dev.guardac.libs.kotlin")
    }
    build {
        dependsOn(shadowJar)
    }
}
