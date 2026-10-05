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
    // State serialization. Gson ships with both Paper and Fabric servers, so hosts need not shade it.
    api("com.google.code.gson:gson:2.14.0")
    // World/volcano definitions (world.yaml, volcanoes/<id>.yaml). YAML 1.2, no object construction
    // from tags (safe by design), small and dependency-free.
    implementation("org.snakeyaml:snakeyaml-engine:3.2")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("perf")
    }
    // Test classes run in parallel JVMs (each test is self-contained; results are deterministic).
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
}

// Performance smoke tests: ./gradlew :engine:perfTest
val perfTest by tasks.registering(Test::class) {
    description = "Runs performance smoke tests (tagged 'perf')."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("perf")
    }
    testLogging.showStandardStreams = true
}
