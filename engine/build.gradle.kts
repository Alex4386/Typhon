// typhon-engine: platform-independent volcano simulation.
// This module must never depend on Minecraft, Bukkit, Paper or Fabric.
plugins {
    `java-library`
}

group = "me.alex4386.typhon"
version = "1.0.0-SNAPSHOT"

// Compile for Java 21 (the floor for Paper and Fabric) with whatever JDK runs Gradle.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
