plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.postgresql:postgresql:42.7.9")
    implementation("org.jetbrains.exposed:exposed-core:1.5.0")
    implementation("org.jetbrains.exposed:exposed-dao:1.5.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.5.0")
    implementation("org.jetbrains.exposed:exposed-java-time:1.5.0")
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-netty:3.5.2")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
    implementation("io.ktor:ktor-server-auth:3.5.2")
    implementation("io.ktor:ktor-server-auth-jwt:3.5.2")
}

application {
    // App.kt compiles to a top-level class named "AppKt" - this tells the fat
    // jar which main() to run when Railway (or you, locally) does `java -jar`.
    mainClass.set("org.example.AppKt")
}

kotlin {
    // Lowered from 25 to 21 (a widely available, stable LTS release) so the
    // Docker build/runtime images used for deployment are reliable. Nothing
    // in this codebase depends on anything newer than 21.
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
