package ch.arcticsoft.springchat3

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class SpringChat3ApplicationTests {

    @Test
    fun contextLoads() {
        // Verifies the Spring context (including Embabel's autoconfiguration)
        // wires up cleanly. Requires an Ollama endpoint to be reachable per
        // application.yml - point OLLAMA_BASE_URL elsewhere if needed.
    }
}
