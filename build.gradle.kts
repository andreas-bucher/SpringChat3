import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "3.5.9"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
}

group = "ch.arctcisoft"
version = "0.1.0-SNAPSHOT"
description = "SpringChat3 - a Kotlin + Spring Boot + WebFlux chat application built on the Embabel agent framework"

// Embabel is released to its own Artifactory instance rather than Maven Central.
// See https://github.com/embabel/embabel-agent for the latest coordinates/version.
val embabelAgentVersion = "1.0.0"
val springAiVersion = "1.1.7"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven {
        name = "embabel-releases"
        url = uri("https://repo.embabel.com/artifactory/libs-release")
        mavenContent { releasesOnly() }
    }
    maven {
        name = "embabel-snapshots"
        url = uri("https://repo.embabel.com/artifactory/libs-snapshot")
        mavenContent { snapshotsOnly() }
    }
    maven { url = uri("https://repo.spring.io/milestone") }
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
    }
}

dependencies {
    // Reactive web layer
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Embabel agent platform (base starter, no predefined shell/CLI mode - we expose it via WebFlux)
    implementation("com.embabel.agent:embabel-agent-starter:$embabelAgentVersion")
    // Local model provider: Ollama (https://ollama.com) running at localhost:11434 by default
    implementation("com.embabel.agent:embabel-agent-starter-ollama:$embabelAgentVersion")

    // Kotlin niceties
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Langfuse tracing (LangfuseTracing.kt, config package): the OpenTelemetry
    // Java SDK, used directly (not via Micrometer/spring-boot-starter-actuator's
    // management.otlp.* autoconfiguration - see that file's doc comment for
    // why) to export spans built from Embabel's own AgenticEventListener
    // events over OTLP to Langfuse. Versions come from Spring Boot's own
    // dependency-management BOM (applied by the plugin above), which already
    // pins compatible versions for both artifacts - same two artifacts Spring
    // Boot's own management.otlp.tracing.* support is built on.
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.embabel.agent:embabel-agent-test:$embabelAgentVersion")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Native macOS DNS resolver for Netty (Apple Silicon) - silences the
    // "Unable to load MacOSDnsServerAddressStreamProvider" warning at startup.
    implementation("io.netty:netty-resolver-dns-native-macos") {
        artifact { classifier = "osx-aarch_64" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// TLS keystore: no build-time generation here (deliberately reverted
// 2026-08-21 - see springchat3_deployment.md in project memory for the
// full history). The keystore is a manually-managed, password-protected
// PKCS12 file at src/main/resources/tls/keystore.p12 - Gradle's default
// resource processing picks it up and embeds it in the jar with no extra
// wiring needed. It's covered by .gitignore (**/tls/keystore.p12, *.p12)
// so it never reaches git despite living in a tracked source directory -
// that means it has to be placed by hand on every machine that builds a
// deployable jar (it won't come along with `git clone`), and regenerated
// by hand whenever the underlying cert renews. Build it with:
//   openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem \
//     -name springchat3 -out src/main/resources/tls/keystore.p12
// (key-alias in application.yml's server.ssl must match the -name above).
