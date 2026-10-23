import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.2"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
    `java-library`
}

group = "io.github.solitaire"
version = providers.gradleProperty("version").get()

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
    sourceSets {
        main {
            kotlin.srcDirs(
                "src/main/kotlin",
                "modules/combined/src/main/kotlin",
                "modules/island/src/main/kotlin",
                "modules/lyrics/src/main/kotlin",
            )
        }
        test {
            kotlin.srcDirs("modules/island/src/test/kotlin", "modules/combined/src/test/kotlin")
        }
    }
}

sourceSets {
    main {
        java.srcDirs(
            "modules/combined/src/main/java",
            "modules/island/src/main/java",
            "modules/lyrics/src/main/java",
        )
    }
}

val workshop = "com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23"
val projectUrl = providers.gradleProperty("projectUrl")
val currentOs = OperatingSystem.current()
val isWindows = currentOs.isWindows
val metadataSources = configurations.create("metadataSources") { isTransitive = false }
val skikoVersion = "0.150.2"

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(workshop) { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")

    compileOnly("net.java.dev.jna:jna:5.17.0")
    compileOnly("net.java.dev.jna:jna-platform:5.17.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.houbb:opencc4j:1.14.0")
    implementation("io.github.moriafly:salt-ui:3.0.0-beta03")
    compileOnly("org.jetbrains.compose.desktop:desktop:1.12.2")
    implementation("org.jetbrains.skiko:skiko-awt:$skikoVersion")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:$skikoVersion")

    metadataSources("net.jthink:jaudiotagger:3.0.1:sources")
    testImplementation(kotlin("stdlib"))
    testImplementation(workshop) { isTransitive = false }
    testImplementation("org.pf4j:pf4j:3.12.0")
    testImplementation("org.jetbrains.compose.desktop:desktop:1.12.2")
    testImplementation("net.java.dev.jna:jna:5.17.0")
    testImplementation("net.java.dev.jna:jna-platform:5.17.0")
}

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.compose.desktop")
    listOf("animation", "foundation", "material", "material3", "runtime", "ui", "components")
        .forEach { exclude(group = "org.jetbrains.compose.$it") }
    listOf("animation", "foundation", "material", "material3", "runtime", "ui")
        .forEach { exclude(group = "androidx.compose.$it") }
    exclude(group = "androidx.lifecycle")
    exclude(group = "org.jetbrains.androidx.lifecycle")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

val preferenceSources = listOf(
    layout.projectDirectory.file("modules/island/src/main/resources/preference_config.json"),
    layout.projectDirectory.file("modules/lyrics/src/main/resources/preference_config.json"),
)
val mergedPreferenceConfig = layout.buildDirectory.file("generated/resources/preference_config.json")
val mergePreferences = tasks.register("mergePreferences") {
    inputs.files(preferenceSources)
    outputs.file(mergedPreferenceConfig)
    doLast {
        val configs = preferenceSources.flatMap { source ->
            @Suppress("UNCHECKED_CAST")
            val root = groovy.json.JsonSlurper().parse(source.asFile) as Map<*, *>
            root["configs"] as List<Any?>
        }
        val json = groovy.json.JsonOutput.prettyPrint(
            groovy.json.JsonOutput.toJson(linkedMapOf("configs" to configs)),
        )
        val readable = Regex("""\\u([0-9a-fA-F]{4})""").replace(json) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
        val target = mergedPreferenceConfig.get().asFile
        target.parentFile.mkdirs()
        target.writeText(readable, Charsets.UTF_8)
    }
}

tasks.processResources {
    dependsOn(mergePreferences)
    doFirst { check(isWindows) { "Build the Windows-only plugin on Windows with the native toolchains installed" } }
    from(mergedPreferenceConfig)
    from("modules/island/native/shared-fonts/MiSansVF.ttf") { into("fonts") }
    from("modules/island/native/shared-fonts/PingFang.ttc") { into("fonts") }
    dependsOn("buildSpectrum", "buildWindowsTray", "buildWindowsGlass", "buildSpout")
    from(layout.buildDirectory.file("native/spw-spectrum.exe")) { into("native") }
    from(layout.buildDirectory.file("native/spw-island-tray.exe")) { into("native") }
    from(layout.buildDirectory.file("native/spw-spout.dll")) { into("native") }
    from(layout.buildDirectory.file("native/spw-island-glass.dll")) { into("native") }
    from({
        zipTree(configurations.runtimeClasspath.get().single {
            it.name.startsWith("skiko-awt-runtime-windows-x64-")
        })
    }) {
        into("native/compose")
        include("skiko-windows-x64.dll", "icudtl.dat")
    }
    inputs.property("projectUrl", projectUrl)
    filesMatching("project.properties") { expand("projectUrl" to projectUrl.get()) }
}

tasks.test {
    // The island verification programs are JavaExec entry points, not JUnit tests.
    failOnNoDiscoveredTests = false
}

tasks.register<JavaExec>("combinedChecks") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.solitaire.spwlyricsisland.CombinedChecksKt")
    jvmArgs("-Djava.awt.headless=true")
}
tasks.register<JavaExec>("spoutChecks") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.solitaire.spwisland.ui.SpoutChecksKt")
    jvmArgs("-Djava.awt.headless=true")
}
tasks.register<JavaExec>("spoutSoak") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.solitaire.spwisland.ui.SpoutSoak")
    args(providers.gradleProperty("soakSeconds").orElse("660").get())
}
tasks.register<JavaExec>("spoutLifecycle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.solitaire.spwisland.ui.SpoutLifecycle")
}

tasks.register<Exec>("buildSpectrum") {
    val output = layout.buildDirectory.file("native/spw-spectrum.exe")
    inputs.files(fileTree("modules/island/native") { include("*.cs") })
    outputs.file(output)
    onlyIf { isWindows }
    doFirst { output.get().asFile.parentFile.mkdirs() }
    executable = "${System.getenv("WINDIR") ?: "C:/Windows"}/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
    args(
        "/nologo", "/target:winexe", "/platform:x64", "/optimize+", "/out:${output.get().asFile.absolutePath}",
        file("modules/island/native/AudioInterop.cs").absolutePath,
        file("modules/island/native/Spectrum.cs").absolutePath,
        file("modules/island/native/ProcessLoopback.cs").absolutePath,
        file("modules/island/native/SpectrumLevels.cs").absolutePath,
    )
}

tasks.register<Exec>("buildWindowsTray") {
    val output = layout.buildDirectory.file("native/spw-island-tray.exe")
    inputs.files("modules/island/native/Tray.cs", "modules/island/native/NotificationIcon.cs")
    outputs.file(output)
    onlyIf { isWindows }
    doFirst { output.get().asFile.parentFile.mkdirs() }
    executable = "${System.getenv("WINDIR") ?: "C:/Windows"}/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
    args(
        "/nologo", "/target:winexe", "/platform:x64", "/optimize+",
        "/reference:System.Windows.Forms.dll", "/reference:System.Drawing.dll",
        "/out:${output.get().asFile.absolutePath}", file("modules/island/native/Tray.cs").absolutePath,
        file("modules/island/native/NotificationIcon.cs").absolutePath,
    )
}

tasks.register<Exec>("configureSpout") {
    onlyIf { isWindows }
    inputs.files(fileTree("modules/island/native/spout"))
    outputs.file(layout.buildDirectory.file("spout/CMakeCache.txt"))
    commandLine("cmake", "-S", "modules/island/native/spout", "-B", "build/spout", "-A", "x64")
}
tasks.register<Exec>("buildSpout") {
    dependsOn("configureSpout")
    onlyIf { isWindows }
    inputs.files(fileTree("modules/island/native/spout"))
    outputs.file(layout.buildDirectory.file("native/spw-spout.dll"))
    commandLine("cmake", "--build", "build/spout", "--config", "Release", "--parallel", "4")
    doLast {
        copy { from("build/spout/Release/spw-spout.dll"); into("build/native") }
    }
}
tasks.register<Exec>("buildWindowsGlass") {
    val output = layout.buildDirectory.file("native/spw-island-glass.dll")
    inputs.file("modules/island/native/glass/DesktopCapture.cpp")
    inputs.file("modules/island/native/glass/DesktopReadback.h")
    inputs.file("modules/island/native/glass/DesktopViewport.h")
    inputs.file("modules/island/native/glass/DesktopSharedTexture.h")
    inputs.file("modules/island/tools/build-glass.ps1")
    outputs.file(output)
    onlyIf { isWindows }
    executable = "powershell.exe"
    args(
        "-NoProfile", "-File", file("modules/island/tools/build-glass.ps1").absolutePath,
        "-Output", output.get().asFile.absolutePath,
    )
}

spmod {
    PluginClass = "io.github.solitaire.spwlyricsisland.SPWLyricsIslandPlugin"
    PluginId = "io.github.solitaire.spwlyricsisland"
    PluginName = "SPW Lyrics Island"
    PluginVersion = project.version.toString()
    PluginProvider = "solitaire + 大肥鱼（感谢原作者 GaBoron）"
    PluginDescription = "Lyrics island plus multi-source lyrics fetching for Salt Player for Windows; Windows x64 with OBS Spout2 output."
    PluginHasConfig = true
    PluginOpenSourceUrl = projectUrl.get()
    PluginPermissions = listOf(
        com.xuncorp.spw.workshop.gradle.PluginPermission.LIBRARY_READ,
        com.xuncorp.spw.workshop.gradle.PluginPermission.KEY_BINDINGS,
        com.xuncorp.spw.workshop.gradle.PluginPermission.CLASS_TRANSFORM,
    )
}

tasks.register<Zip>("sourceArchive") {
    archiveFileName.set("spw-lyrics-island-${project.version}-source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src") { into("src") }
    from("modules/combined") { into("modules/combined") }
    from("modules/island") {
        into("modules/island")
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/__pycache__/**", "**/*.pyc")
    }
    from("modules/lyrics") {
        into("modules/lyrics")
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/__pycache__/**", "**/*.pyc")
    }
    from("gradle") { into("gradle") }
    from("scripts") { into("scripts") }
    from("docs") { into("docs") }
    from("licenses") { into("licenses") }
    from(
        "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
        "README.md", "LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", ".gitignore",
    )
}

tasks.named<Zip>("plugin") {
    val libraryNames by lazy {
        val artifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        val duplicates = artifacts.groupBy { it.file.name }.filterValues { it.size > 1 }.keys
        artifacts.associate { artifact ->
            artifact.file.canonicalPath to if (artifact.file.name in duplicates) {
                "${artifact.moduleVersion.id.group}-${artifact.file.name}"
            } else artifact.file.name
        }
    }
    dependsOn(tasks.jar, "sourceArchive", "spoutChecks", "combinedChecks")
    archiveFileName.set("spw-lyrics-island-${project.version}.spmod")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    eachFile {
        if (path.startsWith("lib/")) {
            name = libraryNames[file.canonicalPath] ?: name
        }
    }
    into("licenses") { from("licenses") }
    into("source") { from(tasks.named("sourceArchive")); from(metadataSources) }
    from("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md", "README.md")
    exclude("**/kotlin-stdlib-*.jar", "**/annotations-*.jar")
}
