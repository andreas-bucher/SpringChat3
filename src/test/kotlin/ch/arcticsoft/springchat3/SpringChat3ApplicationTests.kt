package ch.arcticsoft.springchat3

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

// The secrets below have no default in application.yml on purpose (startup
// must fail fast if they are unset), but the test JVM never sources .env, so
// the context cannot load without stand-in values here.
@SpringBootTest(
    properties = [
        "springchat3.google.picker-api-key=test-picker-key",
        "spring.security.oauth2.client.registration.google.client-id=test-client-id",
        "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
        "server.ssl.enabled=false",
        "server.ssl.key-store-password=test",
    ]
)
class SpringChat3ApplicationTests {

    @Test
    fun contextLoads() {
        // Verifies the Spring context (including Embabel's autoconfiguration)
        // wires up cleanly. Requires an Ollama endpoint to be reachable per
        // application.yml - point OLLAMA_BASE_URL elsewhere if needed.
    }
}
