plugins {
    kotlin("jvm") version "1.9.24"
}

group = "io.linuxdospace"
version = "0.1.0-alpha.2"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
