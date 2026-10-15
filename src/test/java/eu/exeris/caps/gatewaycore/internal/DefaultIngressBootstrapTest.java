package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.testutil.RecordingHttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultIngressBootstrap")
class DefaultIngressBootstrapTest {

    private DefaultGatewayLifecycle lifecycle;
    private DefaultIngressBootstrap ingress;

    @BeforeEach
    void setUp() {
        lifecycle = new DefaultGatewayLifecycle();
        ingress = new DefaultIngressBootstrap(lifecycle);
    }

    @Test
    @DisplayName("rejects traffic with 503 SERVICE_UNAVAILABLE when gateway is not in READY state")
    void rejectsTrafficWhenNotReady() throws Exception {
        RecordingHttpExchange ex1 = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/test", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(ex1);
        assertThat(ex1.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(lifecycle.servedRequestCount()).isZero();

        lifecycle.initialize();
        RecordingHttpExchange ex2 = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/test", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(ex2);
        assertThat(ex2.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(lifecycle.servedRequestCount()).isZero();

        lifecycle.ready();
        RecordingHttpExchange exReady = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/test", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exReady);
        // Routed past lifecycle gate
        assertThat(lifecycle.servedRequestCount()).isEqualTo(1);

        lifecycle.drain(Duration.ofSeconds(10));
        RecordingHttpExchange exDrain = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/test", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exDrain);
        assertThat(exDrain.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        lifecycle.terminate();
        RecordingHttpExchange exTerm = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/test", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exTerm);
        assertThat(exTerm.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("dispatches to registered path prefix handlers and increments served request count")
    void dispatchesToPrefixHandlers() throws Exception {
        lifecycle.initialize();
        lifecycle.ready();

        AtomicBoolean apiCalled = new AtomicBoolean();
        AtomicBoolean staticCalled = new AtomicBoolean();

        ingress.registerHandler("/api", ex -> {
            apiCalled.set(true);
            ex.respond(HttpStatus.OK);
        });
        ingress.registerHandler("/static", ex -> {
            staticCalled.set(true);
            ex.respond(HttpStatus.OK);
        });

        assertThat(ingress.registeredHandlers()).hasSize(2);

        RecordingHttpExchange exApi = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/v1/users", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exApi);

        assertThat(apiCalled).isTrue();
        assertThat(exApi.status()).isEqualTo(HttpStatus.OK);
        assertThat(lifecycle.servedRequestCount()).isEqualTo(1);

        RecordingHttpExchange exStatic = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/static/bundle.js", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exStatic);

        assertThat(staticCalled).isTrue();
        assertThat(exStatic.status()).isEqualTo(HttpStatus.OK);
        assertThat(lifecycle.servedRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("longest prefix takes precedence over shorter matching prefix")
    void longestPrefixPrecedence() throws Exception {
        lifecycle.initialize();
        lifecycle.ready();

        AtomicReference<String> matched = new AtomicReference<>();

        ingress.registerHandler("/api", ex -> {
            matched.set("api-v1");
            ex.respond(HttpStatus.OK);
        });
        ingress.registerHandler("/api/v2", ex -> {
            matched.set("api-v2");
            ex.respond(HttpStatus.OK);
        });

        RecordingHttpExchange ex = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/v2/products", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(ex);

        assertThat(matched.get()).isEqualTo("api-v2");
    }

    @Test
    @DisplayName("unregisters prefix handler correctly")
    void unregistersHandler() throws Exception {
        lifecycle.initialize();
        lifecycle.ready();

        ingress.registerHandler("/api", ex -> ex.respond(HttpStatus.OK));
        assertThat(ingress.registeredHandlers()).containsKey("/api");

        boolean removed = ingress.unregisterHandler("/api");
        assertThat(removed).isTrue();
        assertThat(ingress.registeredHandlers()).doesNotContainKey("/api");

        RecordingHttpExchange ex = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/test", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(ex);
        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("dispatches to delegate handler when no prefix matches")
    void dispatchesToDelegate() throws Exception {
        lifecycle.initialize();
        lifecycle.ready();

        AtomicBoolean delegateCalled = new AtomicBoolean();
        ingress.setDelegateHandler(ex -> {
            delegateCalled.set(true);
            ex.respond(HttpStatus.ACCEPTED);
        });

        assertThat(ingress.delegateHandler()).isNotNull();

        RecordingHttpExchange ex = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.POST, "/fallback/action", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(ex);

        assertThat(delegateCalled).isTrue();
        assertThat(ex.status()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("returns 404 NOT_FOUND when neither prefix nor delegate matches")
    void returns404WhenNoHandlerMatches() throws Exception {
        lifecycle.initialize();
        lifecycle.ready();

        RecordingHttpExchange ex = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/unknown", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(ex);

        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("returns 500 INTERNAL_SERVER_ERROR when handler throws an unhandled exception")
    void returns500OnHandlerException() throws Exception {
        lifecycle.initialize();
        lifecycle.ready();

        ingress.registerHandler("/error", ex -> {
            throw new RuntimeException("Simulated handler crash");
        });

        RecordingHttpExchange ex = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/error/boom", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(ex);

        assertThat(ex.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("executes pre-routing policy and short-circuits before handler dispatch")
    void shortCircuitsWithPreRoutingPolicy() throws Exception {
        lifecycle.initialize();
        lifecycle.ready();

        AtomicBoolean handlerCalled = new AtomicBoolean();
        ingress.registerHandler("/api", ex -> {
            handlerCalled.set(true);
            ex.respond(HttpStatus.OK);
        });

        ingress.policyChain().addPolicy(new eu.exeris.caps.gatewaycore.api.GatewayPolicy() {
            @Override
            public String name() {
                return "blocker";
            }

            @Override
            public eu.exeris.caps.gatewaycore.api.PolicyResult preRouting(eu.exeris.kernel.spi.http.HttpExchange exchange) {
                return eu.exeris.caps.gatewaycore.api.PolicyResult.shortCircuit(HttpStatus.TOO_MANY_REQUESTS);
            }
        });

        RecordingHttpExchange ex = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/data", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(ex);

        assertThat(ex.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(handlerCalled).isFalse();
    }

    @Test
    @DisplayName("executes post-routing policy and modifies handler response headers")
    void decoratesResponseWithPostRoutingPolicy() throws Exception {
        lifecycle.initialize();
        lifecycle.ready();

        ingress.registerHandler("/api", ex -> ex.respond(HttpStatus.OK));

        ingress.policyChain().addPolicy(new eu.exeris.caps.gatewaycore.api.GatewayPolicy() {
            @Override
            public String name() {
                return "header-decorator";
            }

            @Override
            public HttpResponse postRouting(eu.exeris.kernel.spi.http.HttpExchange exchange, HttpResponse response) {
                List<eu.exeris.kernel.spi.http.HttpHeader> headers = new java.util.ArrayList<>(response.headers());
                headers.add(new eu.exeris.kernel.spi.http.HttpHeader("X-Gateway-Decorated", "true"));
                return new HttpResponse(response.status(), response.version(), headers, response.body());
            }
        });

        RecordingHttpExchange ex = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/data", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(ex);

        assertThat(ex.status()).isEqualTo(HttpStatus.OK);
        assertThat(ex.response().headers())
                .anyMatch(h -> h.nameEqualsIgnoreCase("X-Gateway-Decorated") && h.value().equals("true"));
    }

    @Test
    @DisplayName("registeredHandlers is a snapshot: later changes do not appear in a map already returned")
    void registeredHandlersIsSnapshot() {
        ingress.registerHandler("/a", ex -> ex.respond(HttpStatus.OK));
        var snapshot = ingress.registeredHandlers();

        ingress.registerHandler("/b", ex -> ex.respond(HttpStatus.OK));
        ingress.unregisterHandler("/a");

        assertThat(snapshot).containsOnlyKeys("/a");
        assertThat(ingress.registeredHandlers()).containsOnlyKeys("/b");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> snapshot.put("/c", ex -> { }))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
