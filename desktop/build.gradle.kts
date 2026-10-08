plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The brain on a Windows/Linux/macOS computer, for when there is no Android at hand: the same
// core, server and web UI, talking to the hand control through a COM port (jSerialComm).
kotlin {
    jvmToolchain(17)
}

sourceSets {
    // The web UI is packaged in the jar (web/…), so the program runs from any folder.
    main { resources.srcDir("../app/src/main/assets") }
}

dependencies {
    implementation(project(":server"))
    implementation(libs.ktor.server.cio)
    implementation(libs.jserialcomm)
    runtimeOnly(libs.slf4j.nop) // Ktor logs through SLF4J: silence its "no provider" warning
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("org.starbridge.desktop.DesktopKt")
    applicationName = "starbridge-pc"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------- Windows package
// gradlew :desktop:packageWindows → build/release/StarBridge-PC-<version>-windows.zip: a folder
// with StarBridge.exe and its own Java runtime (jpackage): unzip and double-click, nothing to
// install. packaging/windows/StarBridge.iss turns the same folder into an installer (Inno Setup).
val starbridgeVersion = providers.gradleProperty("starbridgeVersion").get()
val jpackageImage = layout.buildDirectory.dir("jpackage")

val packageWindowsImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "StarBridge.exe with a bundled Java runtime (app image)"
    dependsOn("installDist")
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    val lib = layout.buildDirectory.dir("install/starbridge-pc/lib")
    inputs.dir(lib)
    outputs.dir(jpackageImage)
    doFirst {
        delete(jpackageImage)
        val jdk = launcher.get().metadata.installationPath.asFile
        executable = File(jdk, "bin/jpackage" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "").absolutePath
        args(
            "--type", "app-image",
            "--name", "StarBridge",
            "--app-version", starbridgeVersion.substringBefore('-'),
            "--vendor", "StarBridge",
            "--description", "StarBridge - Celestron NexStar telescope control",
            "--input", lib.get().asFile.absolutePath,
            "--main-jar", "desktop.jar",
            "--main-class", "org.starbridge.desktop.DesktopKt",
            "--icon", rootProject.file("packaging/windows/StarBridge.ico").absolutePath,
            "--java-options", "-Dfile.encoding=UTF-8",
            "--dest", jpackageImage.get().asFile.absolutePath,
        )
    }
}

val packageWindows by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Portable Windows zip of the PC program"
    dependsOn(packageWindowsImage)
    from(jpackageImage)
    from(rootProject.file("LICENSE.md")) { into("StarBridge") }
    from(rootProject.file("NOTICE.md")) { into("StarBridge") }
    archiveFileName.set("StarBridge-PC-$starbridgeVersion-windows.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("release"))
}
