// Headless simulator: runs typhon-engine scenarios without Minecraft and writes
// time series, event logs, maps and an HTML report.
plugins {
    application
}

group = "me.alex4386.typhon"
version = "1.0.0-SNAPSHOT"

dependencies {
    implementation(project(":engine"))

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

application {
    mainClass = "me.alex4386.typhon.simulator.Main"
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}

tasks.named<JavaExec>("run") {
    // Resolve --out relative to where Gradle was invoked, not the subproject directory.
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform {
        excludeTags("perf", "slow")
    }
    maxHeapSize = "1g"
    // Test classes run concurrently (classes.default) and preset scenarios within PresetsTest /
    // WorldScenariosTest run in parallel (@Execution(CONCURRENT)); scenarios share no mutable state.
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
}

// Long scenario runs: ./gradlew :simulator:slowTest
val slowTest by tasks.registering(Test::class) {
    description = "Runs long simulator scenarios (tagged 'slow' or 'perf')."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("perf", "slow")
    }
    testLogging.showStandardStreams = true
}
