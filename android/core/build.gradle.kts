// The link protocol client: plain Kotlin, no Android, so it is tested on any
// computer against the same mock device the desktop tool's tests use, and
// against a real board on a serial port.
plugins {
    kotlin("jvm")
}

// Built for Java 17, the level the Android app compiles at, by whatever JDK runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // SSH, for link mode over Wi-Fi (SshTransport): JSch, the maintained fork, which does what a
    // board offers (ECDSA P-256, ECDH or curve25519, AES-GCM) with the platform's own crypto.
    implementation("com.github.mwiede:jsch:0.2.20")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
    // Only for the test that drives a board on this computer's serial port.
    testImplementation("com.fazecast:jSerialComm:2.11.0")
}

tasks.test {
    useJUnitPlatform()
    // The mock device is Studio's own (../../tests/mockdev.py), run in Python.
    systemProperty("studio.root", rootDir.parentFile.absolutePath)
    System.getenv("RPC_PORT")?.let { systemProperty("rpc.port", it) }
    System.getenv("RPC_PASSWORD")?.let { systemProperty("rpc.password", it) }
    System.getenv("RPC_SSH")?.let { systemProperty("rpc.ssh", it) }
    testLogging { events("failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
