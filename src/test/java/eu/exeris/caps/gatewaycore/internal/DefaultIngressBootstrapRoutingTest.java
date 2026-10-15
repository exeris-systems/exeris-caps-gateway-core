package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.gatewaycore.testutil.RecordingHttpExchange;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultIngressBootstrap: prefix boundaries and typed responses")
class DefaultIngressBootstrapRoutingTest {

    private DefaultGatewayLifecycle lifecycle;
    private DefaultIngressBootstrap ingress;

    @BeforeEach
    void setUp() throws Exception {
        lifecycle = new DefaultGatewayLifecycle();
        ingress = new DefaultIngressBootstrap(lifecycle);
        lifecycle.initialize();
        lifecycle.ready();
    }

    private static RecordingHttpExchange get(String path) {
        return new RecordingHttpExchange(HttpRequest.noBody(HttpMethod.GET, path, HttpVersion.HTTP_1_1, List.of()));
    }

    @Test
    @DisplayName("a prefix matches only at a path segment boundary")
    void prefixMatchesOnSegmentBoundary() {
        List<String> hits = new ArrayList<>();
        ingress.registerHandler("/api", ex -> hits.add("api"));

        ingress.handle(get("/api"));
        ingress.handle(get("/api/users"));
        RecordingHttpExchange sibling = get("/apix");
        ingress.handle(sibling);
        RecordingHttpExchange other = get("/apiary/list");
        ingress.handle(other);

        assertThat(hits).containsExactly("api", "api");
        assertThat(sibling.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a prefix ending in a slash matches everything beneath it")
    void trailingSlashPrefix() {
        List<String> hits = new ArrayList<>();
        ingress.registerHandler("/api/", ex -> hits.add("api"));

        ingress.handle(get("/api/users"));
        RecordingHttpExchange bare = get("/api");
        ingress.handle(bare);

        assertThat(hits).containsExactly("api");
        assertThat(bare.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the longest matching prefix wins")
    void longestPrefixWins() {
        List<String> hits = new ArrayList<>();
        ingress.registerHandler("/api", ex -> hits.add("api"));
        ingress.registerHandler("/api/admin", ex -> hits.add("admin"));

        ingress.handle(get("/api/admin/users"));
        ingress.handle(get("/api/administrators"));

        assertThat(hits).containsExactly("admin", "api");
    }

    @Test
    @DisplayName("post-routing policies see a typed response and their header changes are applied")
    void typedResponsePassesThroughPostRouting() {
        ingress.policyChain().addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "tag";
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public PolicyResult preRouting(HttpExchange exchange) {
                return PolicyResult.proceed();
            }

            @Override
            public HttpResponse postRouting(HttpExchange exchange, HttpResponse response) {
                List<HttpHeader> headers = new ArrayList<>(response.headers());
                headers.add(new HttpHeader("x-policy", "applied"));
                return new HttpResponse(response.status(), response.version(), headers, response.body());
            }
        });
        AtomicReference<HttpTypedResponse> sent = new AtomicReference<>();
        HttpExchange recorder = new HttpExchange() {
            private final HttpRequest request =
                    HttpRequest.noBody(HttpMethod.GET, "/api/x", HttpVersion.HTTP_1_1, List.of());

            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public void respond(HttpResponse response) {
                throw new AssertionError("a typed response must stay typed");
            }

            @Override
            public void respond(HttpTypedResponse response) {
                sent.set(response);
            }
        };
        ingress.registerHandler("/api", ex -> ex.respond(HttpStatus.OK, "payload"));

        ingress.handle(recorder);

        assertThat(sent.get()).isNotNull();
        assertThat(sent.get().payload()).isEqualTo("payload");
        assertThat(sent.get().headers()).contains(new HttpHeader("x-policy", "applied"));
    }

    @Test
    @DisplayName("a policy that throws ends the request with 500 and nothing is dispatched")
    void throwingPolicyFailsClosed() {
        ingress.policyChain().addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public PolicyResult preRouting(HttpExchange exchange) {
                throw new IllegalStateException("policy failure");
            }
        });
        List<String> hits = new ArrayList<>();
        ingress.registerHandler("/api", ex -> hits.add("api"));

        RecordingHttpExchange ex = get("/api/x");
        ingress.handle(ex);

        assertThat(hits).isEmpty();
        assertThat(ex.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
