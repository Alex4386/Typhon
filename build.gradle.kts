// Root project: the Typhon Bukkit/Paper plugin (src/) with the bundled web UI (web/).
// v1 rewrite modules live in subprojects (see settings.gradle.kts).
plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "me.alex4386.plugin"
description = "Minecraft world is SO BORING, Bring the Typhon's power into minecraft world!"

allprojects {
    repositories {
        mavenCentral()
    }
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.bluecolored.de/releases")
    maven("https://maven.playpro.com")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT")
    compileOnly("de.bluecolored:bluemap-api:2.7.6")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.13")
    compileOnly("net.coreprotect:coreprotect:22.4")
    compileOnly("org.jetbrains:annotations:21.0.1")

    implementation("com.googlecode.json-simple:json-simple:1.1") {
        isTransitive = false
    }
    implementation("io.javalin:javalin:6.6.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

// ── Web UI (Vite + React) ──
// Pass -PskipWeb to build the plugin without rebuilding the bundled UI.
val skipWeb = providers.gradleProperty("skipWeb").isPresent

node {
    version = "22.13.1"
    download = true
    nodeProjectDir = file("web")
}

val buildWeb by tasks.registering(com.github.gradle.node.npm.task.NpmTask::class) {
    group = "build"
    description = "Builds the bundled web UI into web/dist."
    dependsOn(tasks.npmInstall)
    args = listOf("run", "build")
    inputs.dir("web/src")
    inputs.files("web/index.html", "web/package.json", "web/package-lock.json", "web/vite.config.ts", "web/tsconfig.json")
    outputs.dir("web/dist")
}

tasks.processResources {
    exclude("web/**")
    filesMatching("plugin.yml") {
        expand("project" to mapOf("version" to project.version))
    }
    if (!skipWeb) {
        dependsOn(buildWeb)
        from("web/dist") {
            into("web")
        }
    }
}

// ── Packaging ──
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName = "typhon"
    archiveClassifier = ""
    mergeServiceFiles()
    filesMatching("META-INF/*.kotlin_module") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    minimize {
        exclude(dependency("io.javalin:javalin:.*"))
        exclude(dependency("org.eclipse.jetty:.*:.*"))
        exclude(dependency("org.eclipse.jetty.ee10:.*:.*"))
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
