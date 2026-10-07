// mc-projection: shows the engine's continuous world model as Minecraft-style blocks.
// The engine keeps metres (layer stacks, uplift, lava and water depths); a block host (Paper, Fabric)
// quantises that state here. Pure Java: no Bukkit, Paper or Fabric dependency.
plugins {
    `java-library`
}

group = "me.alex4386.typhon"
version = "1.0.0-SNAPSHOT"

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    api(project(":engine"))

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
