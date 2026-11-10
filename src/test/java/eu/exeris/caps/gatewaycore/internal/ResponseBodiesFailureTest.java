package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.testutil.CapturingLoggerFinder;
import eu.exeris.caps.gatewaycore.testutil.CountingBody;
import eu.exeris.caps.gatewaycore.testutil.ScriptedHttpExchange;
import eu.exeris.caps.gatewaycore.testutil.ScriptedHttpExchange.Behaviour;
import eu.exeris.caps.gatewaycore.testutil.ThrowingBody;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Response body release failures")
class ResponseBodiesFailureTest {

    private static final String LOGGER = ResponseBodies.class.getName();

    /** An Error subclass that is not one of the JDK's well-known ones. */
    private static final class CloseError extends Error {
        CloseError(String message) {
            super(message);
        }
    }

    private DefaultPolicyChain chain;
    private CountingBody replacement;
    private ScriptedHttpExchange delegate;

    @BeforeEach
    void setUp() {
        CapturingLoggerFinder.clear();
        chain = new DefaultPolicyChain();
        replacement = new CountingBody();
        delegate = new ScriptedHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/x", HttpVersion.HTTP_1_1, List.of()), replacement);
    }

    private static HttpResponse response(HttpStatus status, Object body) {
        var buffer = body instanceof CountingBody c ? c.buffer()
                : body instanceof ThrowingBody t ? t.buffer() : null;
        return new HttpResponse(status, HttpVersion.HTTP_1_1, List.of(), buffer);
    }

    private void policy(String name, int order, BiFunction<HttpExchange, HttpResponse, HttpResponse> post) {
        chain.addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public HttpResponse postRouting(HttpExchange exchange, HttpResponse response) {
                return post.apply(exchange, response);
            }
        });
    }

    // ---- Q2 ----

    @Test
    @DisplayName("Q2: a failing close of a replaced body does not propagate and the replacement still flows")
    void replacedBodyCloseFailureDoesNotPropagate() {
        ThrowingBody original = new ThrowingBody(new IllegalStateException("close failed"));
        HttpResponse replaced = response(HttpStatus.FORBIDDEN, replacement);
        policy("p", 0, (e, r) -> replaced);

        HttpResponse result = chain.executePostRouting(delegate, response(HttpStatus.OK, original));

        assertThat(result).isSameAs(replaced);
        assertThat(original.closes()).isEqualTo(1);
        assertThat(replacement.libraryCloses()).isZero();
    }

    @Test
    @DisplayName("Q2: a failing close does not stop the chain: the next policy still runs and the replacement is handed over")
    void replacedBodyCloseFailureDoesNotStopChain() {
        ThrowingBody original = new ThrowingBody(new CloseError("close failed"));
        int[] reached = {0};
        // post-routing runs in reverse order: "late" first
        policy("late", 2, (e, r) -> response(HttpStatus.FORBIDDEN, replacement));
        policy("early", 1, (e, r) -> {
            reached[0]++;
            return r;
        });

        new PolicyInterceptingHttpExchange(delegate, chain).respond(response(HttpStatus.OK, original));

        assertThat(reached[0]).isEqualTo(1);
        assertThat(original.closes()).isEqualTo(1);
        assertThat(delegate.handed()).hasSize(1);
        assertThat(delegate.handed().get(0).body()).isSameAs(replacement.buffer());
        assertThat(replacement.engineCloses()).isEqualTo(1);
        assertThat(replacement.libraryCloses()).isZero();
    }

    @Test
    @DisplayName("Q2: a failing close is logged once at WARNING with the exception")
    void replacedBodyCloseFailureIsLoggedOnce() {
        IllegalStateException failure = new IllegalStateException("close failed");
        ThrowingBody original = new ThrowingBody(failure);
        policy("p", 0, (e, r) -> response(HttpStatus.FORBIDDEN, replacement));

        chain.executePostRouting(delegate, response(HttpStatus.OK, original));

        List<CapturingLoggerFinder.Record> records = CapturingLoggerFinder.records(LOGGER);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).level()).isEqualTo(System.Logger.Level.WARNING);
        assertThat(records.get(0).thrown()).isSameAs(failure);
    }

    @Test
    @DisplayName("Q2: a successful close of a replaced body logs nothing")
    void successfulCloseLogsNothing() {
        CountingBody original = new CountingBody();
        policy("p", 0, (e, r) -> response(HttpStatus.FORBIDDEN, replacement));

        chain.executePostRouting(delegate, response(HttpStatus.OK, original));

        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(CapturingLoggerFinder.records(LOGGER)).isEmpty();
    }

    // ---- Q3 ----

    @Test
    @DisplayName("Q3: hand-over fails and close throws a RuntimeException: the hand-over failure propagates, close failure suppressed")
    void handOverFailureWinsOverCloseRuntimeException() {
        RuntimeException closeFailure = new IllegalArgumentException("close failed");
        ThrowingBody body = new ThrowingBody(closeFailure);
        delegate.script(Behaviour.THROW_BEFORE_TAKING);

        assertThatThrownBy(() -> new PolicyInterceptingHttpExchange(delegate, chain)
                .respond(response(HttpStatus.OK, body)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("exchange refused the response")
                .satisfies(t -> assertThat(t.getSuppressed()).containsExactly(closeFailure));
        assertThat(body.closes()).isEqualTo(1);
    }

    @Test
    @DisplayName("Q3: hand-over fails and close throws an Error: the hand-over failure propagates, close failure suppressed")
    void handOverFailureWinsOverCloseError() {
        CloseError closeFailure = new CloseError("close failed");
        ThrowingBody body = new ThrowingBody(closeFailure);
        delegate.script(Behaviour.THROW_BEFORE_TAKING);

        assertThatThrownBy(() -> new PolicyInterceptingHttpExchange(delegate, chain)
                .respond(response(HttpStatus.OK, body)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(t -> assertThat(t.getSuppressed()).containsExactly(closeFailure));
        assertThat(body.closes()).isEqualTo(1);
    }

    @Test
    @DisplayName("Q3: a throwing policy and a failing close: the policy failure propagates with the close failure suppressed")
    void policyFailureWinsOverCloseFailure() {
        RuntimeException policyFailure = new RuntimeException("policy failed");
        CloseError closeFailure = new CloseError("close failed");
        ThrowingBody body = new ThrowingBody(closeFailure);
        policy("p", 0, (e, r) -> {
            throw policyFailure;
        });

        assertThatThrownBy(() -> chain.executePostRouting(delegate, response(HttpStatus.OK, body)))
                .isSameAs(policyFailure)
                .satisfies(t -> assertThat(t.getSuppressed()).containsExactly(closeFailure));
        assertThat(body.closes()).isEqualTo(1);
    }

    @Test
    @DisplayName("Q3: a hand-over Error with a failing close keeps the Error as the propagated failure")
    void handOverErrorWinsOverCloseFailure() {
        CloseError handOver = new CloseError("hand-over failed");
        RuntimeException closeFailure = new RuntimeException("close failed");
        ThrowingBody body = new ThrowingBody(closeFailure);
        HttpRequest request = HttpRequest.noBody(HttpMethod.GET, "/x", HttpVersion.HTTP_1_1, List.of());
        HttpExchange failing = new HttpExchange() {
            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public void respond(HttpResponse response) {
                throw handOver;
            }

            @Override
            public void respond(HttpTypedResponse typedResponse) {
                throw handOver;
            }

            @Override
            public void respond(HttpStatus status, Object body) {
                throw handOver;
            }

            @Override
            public void respond(HttpStatus status) {
                throw handOver;
            }
        };

        assertThatThrownBy(() -> new PolicyInterceptingHttpExchange(failing, chain)
                .respond(response(HttpStatus.OK, body)))
                .isSameAs(handOver)
                .satisfies(t -> assertThat(t.getSuppressed()).containsExactly(closeFailure));
    }
}
