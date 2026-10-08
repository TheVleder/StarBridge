plugins {
    alias(libs.plugins.kotlin.jvm)
}

// HTTP + WebSocket routes shared by the Android app and the devserver (Ktor, no Android).
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.websockets)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
}
