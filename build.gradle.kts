import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "3.5.9"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
}

group = "ch.arctcisoft"
version = "0.1.3"
description = "SpringChat3 - a Kotlin + Spring Boot + WebFlux chat application built on the Embabel agent framework"

// Embabel is released to its own Artifactory instance rather than Maven Central.
// See https://github.com/embabel/embabel-agent for the latest coordinates/version.
val embabelAgentVersion = "1.0.0"
// 1.1.8 (bumped from 1.1.7, 2026-08-21): latest patch on the Spring Boot
// 3.x-compatible 1.1.x line - confirmed it still has the same
// org.springframework.ai.tool package shape as 1.1.7 (no ToolCallbacks
// utility class; that's 2.0.x-only), so ChatAgent.kt's
// MethodToolCallbackProvider-based tool-definition logging needed no
// changes for this bump. NOT the same as embabel-agent 1.0.0 above, whose
// own transitive spring-ai-bom import this line intentionally overrides -
// see springchat3_spring_boot_4.md in project memory for why a jump to the
// spring-ai 2.0.x line (which needs Spring Boot 4) isn't possible yet:
// embabel-agent's own Boot-4/Spring-AI-2.0 support is unreleased.
val springAiVersion = "1.1.8"

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

    // Google OAuth2/OIDC login, gating the whole app (2026-08-22, user's own
    // request "add oauth2.0 google authentication" - see
    // springchat3_authentication.md in project memory). Brings in
    // spring-boot-starter-security + spring-security-oauth2-client/-jose
    // transitively - no separate "reactive" flavor of this starter exists;
    // Spring Boot's own autoconfiguration picks the reactive
    // (ReactiveOAuth2ClientAutoConfiguration) vs. servlet wiring based on
    // WebFlux being on the classpath (it already is, above), same detection
    // Spring Boot uses everywhere else in this app. Confirmed the exact
    // artifact coordinate and this reactive/servlet auto-detection against
    // Spring Security's own reference docs before adding this, not assumed.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    // Embabel agent platform (base starter, no predefined shell/CLI mode - we expose it via WebFlux)
    implementation("com.embabel.agent:embabel-agent-starter:$embabelAgentVersion")
    // Local model provider: Ollama (https://ollama.com) running at localhost:11434 by default
    implementation("com.embabel.agent:embabel-agent-starter-ollama:$embabelAgentVersion")

    // PDF text extraction for the document-Q&A feature (2026-08-22) - see
    // springchat3_document_qa.md in project memory. No explicit version
    // here: covered by the spring-ai-bom import above, same as everything
    // else spring-ai-*. Provides org.springframework.ai.reader.pdf.
    // PagePdfDocumentReader - confirmed against Spring AI's own reference
    // docs and API docs (not just assumed) before adding this, given this
    // project's history of one Spring AI API guess turning out wrong
    // (see springchat3_native_tool_calling.md risk #6).
    implementation("org.springframework.ai:spring-ai-pdf-document-reader")

    // Document structure extraction (2026-08-22, see springchat3_document_qa.md
    // in project memory) - DocumentStructureExtractor.kt reads a PDF's
    // embedded outline/bookmarks directly via PDFBox's own API
    // (org.apache.pdfbox.Loader, PDOutlineItem/PDOutlineNode,
    // PDPageDestination), not through Spring AI's PagePdfDocumentReader
    // abstraction above (which only exposes page text, not the outline
    // tree). pdfbox is already on the classpath transitively via
    // spring-ai-pdf-document-reader, but that's an implicit dependency for
    // code that only reads Document objects - since this code calls PDFBox
    // classes directly, it gets its own explicit dependency instead of
    // silently relying on someone else's transitive one. Version 3.0.7
    // pinned to match spring-ai-pdf-document-reader 1.1.8's own pdfbox
    // dependency exactly (confirmed via that artifact's POM on Maven
    // Central, not assumed) - bump this together with springAiVersion if
    // that ever changes, so the two copies on the classpath stay identical.
    // API surface (Loader.loadPDF(byte[]), PDOutlineNode.children(),
    // PDPageDestination.retrievePageNumber()) confirmed against PDFBox's
    // own GitHub source for this exact tag before writing the extractor,
    // given this project's history of API guesses going wrong - PDFBox 3.x
    // in particular removed PDDocument.load(...) entirely in favor of
    // Loader, a real breaking change from the 2.x API most examples online
    // still show.
    implementation("org.apache.pdfbox:pdfbox:3.0.7")

    // Document-Q&A "Phase 2" (2026-08-22, see springchat3_document_qa.md in
    // project memory): real chunking + local embeddings + retrieval,
    // replacing Phase 1's full-text-stuffing-with-truncation. Both entries
    // below are BOM-managed (no explicit version), same pattern as
    // spring-ai-pdf-document-reader above. API surface confirmed against
    // Spring AI's own reference/javadoc pages before writing the code that
    // uses them (TokenTextSplitter's real package turned out to be
    // org.springframework.ai.transformer.splitter, NOT
    // org.springframework.ai.document as an initial doc fetch wrongly
    // suggested - caught by cross-checking against a second, independent
    // source: Maven Central's full-class search) - but two things below
    // remain genuinely UNVERIFIED, flagged in more detail on the beans that
    // depend on them (VectorStoreConfig.kt / DocumentIndex.kt):
    //  1. spring-ai-starter-model-ollama's own OllamaChatModel
    //     autoconfiguration might collide with embabel-agent-ollama's own
    //     chat-model wiring (see HttpClientConfig.kt's doc comment on the
    //     @Qualifier("aiModelRestClientBuilder") beans for how embabel's
    //     own Ollama autoconfigure module works) - if the app fails to
    //     start with a NoUniqueBeanDefinitionException on ChatModel after
    //     this change, this dependency is the first thing to suspect; the
    //     fix would be excluding just its chat autoconfiguration class via
    //     spring.autoconfigure.exclude, keeping only its embedding half.
    //  2. The embedding HTTP client likely does NOT pick up
    //     HttpClientConfig's specially-qualified, generous-timeout
    //     RestClient/WebClient builders (those qualifiers are embabel's own
    //     convention, not something Spring AI's official autoconfiguration
    //     looks for) - so embedding a big first batch of chunks on upload
    //     risks the same ~10s Netty ReadTimeoutException HttpClientConfig.kt
    //     already documents fixing for chat calls. Not addressed here;
    //     watch for it on a real upload of a large PDF.
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("org.springframework.ai:spring-ai-vector-store")

    // Kotlin niceties
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

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

// Generates META-INF/build-info.properties (the Spring Boot Gradle plugin
// wires this ahead of processResources automatically) so a BuildProperties
// bean carrying the Gradle project `version` above is available at runtime -
// backs the small version label in the chat header (see VersionController.kt
// / index.html). Added 2026-08-22.
springBoot {
    buildInfo()
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
