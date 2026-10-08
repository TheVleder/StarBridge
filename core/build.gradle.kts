plugins {
    alias(libs.plugins.kotlin.jvm)
    // Mount and camera simulators live in src/testFixtures: used by the tests and the
    // devserver, never shipped in the APK.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    api(project(":imaging"))
    implementation(libs.zxing.core)
    testFixturesApi(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
