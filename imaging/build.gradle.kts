plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Live stacking engine (EAA): pure Kotlin, no Android, fully testable on a PC.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}
