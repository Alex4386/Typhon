// Simulation server: runs typhon-engine sessions and streams them to the web visualizer over
// WebSocket (protocol v1, docs/protocol.md).
plugins {
    application
}

group = "me.alex4386.typhon"
version = "1.0.0-SNAPSHOT"

dependencies {
    implementation(project(":engine"))
    implementation(project(":simulator")) // presets, world directories, the host-side voxel world
    implementation("io.javalin:javalin:6.6.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

application {
    mainClass = "me.alex4386.typhon.server.Main"
    // Several worlds run side by side in one server; real-scale worlds take a few hundred MB each.
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

tasks.named<JavaExec>("run") {
    // Resolve --world / --worlds-dir / --ui relative to where Gradle was invoked.
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform {
        excludeTags("perf", "slow")
    }
    // world sessions (and the config API test's rebuilds) need more than the 512 MB default
    maxHeapSize = "1g"
    // engine worker threads per test JVM, within the test CPU budget (-Ptyphon.testCpus=N, default 4)
    systemProperty("typhon.threads", ((findProperty("typhon.testCpus") as String?)?.toIntOrNull() ?: 4).toString())
}

// Long tests (e.g. every configuration change applied for real): ./gradlew :sim-server:slowTest
val slowTest by tasks.registering(Test::class) {
    description = "Runs long sim-server tests (tagged 'slow')."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "1g"
    useJUnitPlatform {
        includeTags("slow")
    }
}
