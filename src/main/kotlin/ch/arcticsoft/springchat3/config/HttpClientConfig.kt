package ch.arcticsoft.springchat3.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.net.http.HttpClient as JdkHttpClient
import reactor.netty.http.client.HttpClient as ReactorHttpClient

/**
 * Local Ollama inference can take far longer than typical HTTP client
 * defaults - especially on a cold model load, or with a larger model like
 * magistral:24b. Spring AI's Ollama integration doesn't currently expose a
 * timeout configuration property for this (see
 * https://github.com/spring-projects/spring-ai/issues/3573 and
 * https://github.com/spring-projects/spring-ai/issues/5400), so we override
 * the RestClient/WebClient builders it autowires with more generous
 * read/response timeouts. Bump [readTimeout] further if your models are
 * even slower to respond.
 *
 * IMPORTANT: the `@Qualifier("aiModelRestClientBuilder")` /
 * `"aiModelWebClientBuilder"` annotations below are load-bearing, not
 * decorative. Every embabel-agent model-provider autoconfigure module
 * (OllamaModelsConfig included) only picks up an application-supplied
 * builder bean if it carries that exact qualifier; an unqualified bean of
 * the right type is silently ignored, not autowired-by-type as you'd
 * expect from typical Spring behavior. Without it, Ollama chat calls fall
 * back to a bare `RestClient.builder()`, which - since this app has
 * spring-boot-starter-webflux (Reactor Netty) on the classpath and no
 * Apache HttpClient5/Jetty - auto-selects `ReactorClientHttpRequestFactory`,
 * whose default constructor hardcodes a 10-second response timeout
 * (see ReactorClientHttpRequestFactory.defaultInitializer in spring-web).
 * That 10s silently overrides everything below and is why Ollama calls used
 * to fail with io.netty.handler.timeout.ReadTimeoutException on anything
 * slower than 10s (basically any real inference), even though this class
 * "worked" for the app's own tool HTTP calls (GeoTool), which
 * take an unqualified RestClient.Builder and don't care about the qualifier.
 */
@Configuration
class HttpClientConfig {

    private val readTimeout: Duration = Duration.ofMinutes(5)

    @Bean
    @Qualifier("aiModelRestClientBuilder")
    fun restClientBuilder(): RestClient.Builder {
        val jdkClient = JdkHttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(jdkClient)
        requestFactory.setReadTimeout(readTimeout)
        return RestClient.builder().requestFactory(requestFactory)
    }

    @Bean
    @Qualifier("aiModelWebClientBuilder")
    fun webClientBuilder(): WebClient.Builder {
        val reactorClient = ReactorHttpClient.create()
            .responseTimeout(readTimeout)
        return WebClient.builder().clientConnector(ReactorClientHttpConnector(reactorClient))
    }
}
