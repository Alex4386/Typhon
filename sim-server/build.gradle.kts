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
    applicationDefaultJvmArgs = listOf("-Xmx3g")
}

tasks.named<JavaExec>("run") {
    // Resolve --world / --worlds-dir / --ui relative to where Gradle was invoked.
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform {
        excludeTags("perf", "slow")
    }
}
