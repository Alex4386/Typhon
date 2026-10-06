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
        excludeTags("perf", "slow", "validation", "flow")
    }
    // Real-scale presets (now with a full subsurface model each) run concurrently: 1 GB is too small.
    maxHeapSize = "4g"
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

// End-to-end flow on the island preset (create, terrain, water, eruption to the sea, live dial,
// save/restore, thread count; prints a report): ./gradlew :simulator:islandFlow
val islandFlow by tasks.registering(Test::class) {
    description = "Runs the island test flow (tagged 'flow') and prints its report."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("flow")
    }
    maxHeapSize = "3g"
    outputs.upToDateWhen { false } // a flow is run to be watched
    testLogging.showStandardStreams = true
}

// Validation against observations (real-scale presets at their reference horizons, ~20 min):
// ./gradlew :simulator:validationTest   (report in simulator/build/validation)
val validationTest by tasks.registering(Test::class) {
    description = "Runs the validation suite (tagged 'validation') against literature reference values."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("validation")
    }
    maxHeapSize = "3g"
    systemProperty("typhon.validation.out", layout.buildDirectory.dir("validation").get().asFile.absolutePath)
    testLogging.showStandardStreams = true
}
