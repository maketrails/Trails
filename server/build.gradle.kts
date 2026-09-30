plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.application)
}

group = "es.jvbabi.trails"
version = "1.0.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        freeCompilerArgs.add("-Xskip-prerelease-check")
    }
}

dependencies {
    implementation(projects.shared)

    // Ktor Server
    implementation(libs.server.ktor.server.core)
    implementation(libs.server.ktor.server.netty)
    implementation(libs.server.ktor.server.websockets)
    implementation(libs.server.ktor.server.content.negotiation)
    implementation(libs.server.ktor.server.auth.jwt)
    implementation(libs.server.ktor.server.call.logging)
    implementation(libs.server.ktor.server.status.pages)
    implementation(libs.server.ktor.server.sse)
    implementation(libs.server.ktor.server.default.headers)
    implementation(libs.server.ktor.server.cors)
    implementation(libs.server.ktor.serialization.kotlinx.json)

    // Ktor Client
    implementation(libs.server.ktor.client.core)
    implementation(libs.server.ktor.client.cio)
    implementation(libs.server.ktor.client.content.negotiation)

    // CLI
    implementation(libs.server.clikt)

    // Database
    implementation(libs.server.exposed.core)
    implementation(libs.server.exposed.dao)
    implementation(libs.server.exposed.jdbc)
    implementation(libs.server.exposed.kotlin.datetime)
    implementation(libs.server.sqlite)
    implementation(libs.server.postgres)

    // DI & Logging
    implementation(libs.server.koin.ktor)
    implementation(libs.server.koin.loggerSlf4j)
    implementation(libs.server.logback.classic)

    // Utilities
    implementation(libs.server.bcrypt)
    implementation(libs.server.csv)
    implementation(libs.server.gson)
    implementation(libs.server.kotlinx.datetime)

    // Auth - private GitHub package
    implementation(libs.server.authentikt)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.server.ktor.server.test.host)
}

application {
    mainClass.set("es.jvbabi.trails.MainKt")
}

/*
 * Developer tools that run the server's own code against its data, kept out of the
 * server JAR. Associated with main, so they see its internal declarations.
 */
val tools: SourceSet by sourceSets.creating

configurations[tools.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[tools.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

kotlin.target.compilations.named("tools") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.register<JavaExec>("speedChart") {
    group = "tools"
    description = "Charts speed and movement mode of a device as HTML, see SpeedChart.kt. Pass options with --args."
    classpath = tools.runtimeClasspath
    mainClass.set("es.jvbabi.trails.tools.SpeedChartKt")
    workingDir = rootDir
}

tasks.register<Jar>("buildServerJar") {
    group = "build"
    description = "Assembles a fat JAR with all dependencies bundled."
    archiveBaseName.set(project.name)
    archiveClassifier.set("fat")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    }
}
