plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Development only: runs the real core (hub, brain, protocol) against the byte-level
// simulator and serves the web UI from app/src/main/assets/web. Not part of the app.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":server"))
    implementation(testFixtures(project(":core"))) // the mount and camera simulators
    implementation(libs.ktor.server.cio)
}

application {
    mainClass.set("org.starbridge.dev.DevServerKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
