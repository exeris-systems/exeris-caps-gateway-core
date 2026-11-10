package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.gatewaycore.testutil.CountingBody;
import eu.exeris.caps.gatewaycore.testutil.ScriptedHttpExchange;
import eu.exeris.caps.gatewaycore.testutil.ScriptedHttpExchange.Behaviour;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Short-circuit response body ownership")
class ShortCircuitBodyOwnershipTest {

    private DefaultIngressBootstrap ingress;
    private CountingBody body;

    @BeforeEach
    void setUp() throws Exception {
        DefaultGatewayLifecycle lifecycle = new DefaultGatewayLifecycle();
        lifecycle.initialize();
        lifecycle.ready();
        ingress = new DefaultIngressBootstrap(lifecycle);
        body = new CountingBody();
    }

    private void shortCircuitWith(HttpResponse response) {
        ingress.policyChain().addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "blocker";
            }

            @Override
            public PolicyResult preRouting(HttpExchange exchange) {
                return PolicyResult.shortCircuit(response);
            }
        });
    }

    private HttpResponse forbiddenWith(CountingBody withBody) {
        return new HttpResponse(HttpStatus.FORBIDDEN, HttpVersion.HTTP_1_1, List.of(),
                withBody == null ? null : withBody.buffer());
    }

    private ScriptedHttpExchange exchange(Behaviour... script) {
        return new ScriptedHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/x", HttpVersion.HTTP_1_1, List.of()), body)
                .script(script);
    }

    @Test
    @DisplayName("R1: a handed-over body is released once by the engine and never by the gateway")
    void handOverSucceeds() {
        shortCircuitWith(forbiddenWith(body));
        ScriptedHttpExchange ex = exchange(Behaviour.ACCEPT);

        ingress.handle(ex);

        assertThat(ex.handed()).hasSize(1);
        assertThat(ex.handed().get(0).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ex.handed().get(0).body()).isSameAs(body.buffer());
        assertThat(body.engineCloses()).isEqualTo(1);
        assertThat(body.libraryCloses()).isZero();
    }

    @Test
    @DisplayName("R2: when respond throws before taking the body the gateway releases it once and answers 500")
    void respondThrowsBeforeTaking() {
        shortCircuitWith(forbiddenWith(body));
        ScriptedHttpExchange ex = exchange(Behaviour.THROW_BEFORE_TAKING, Behaviour.ACCEPT);

        ingress.handle(ex);

        assertThat(body.libraryCloses()).isEqualTo(1);
        assertThat(body.engineCloses()).isZero();
        assertThat(ex.handed()).hasSize(2);
        assertThat(ex.handed().get(1).status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ex.handed().get(1).body()).isNull();
    }

    @Test
    @DisplayName("R6: when the hand-over fails and the failure answer fails too the body is still released once")
    void doubleFailure() {
        shortCircuitWith(forbiddenWith(body));
        ScriptedHttpExchange ex = exchange(Behaviour.THROW_BEFORE_TAKING, Behaviour.THROW_BEFORE_TAKING);

        ingress.handle(ex);

        assertThat(ex.handed()).hasSize(2);
        assertThat(body.libraryCloses()).isEqualTo(1);
        assertThat(body.engineCloses()).isZero();
    }

    @Test
    @DisplayName("R2: when respond throws after the engine took the body the engine's release stays the only one and 500 is answered")
    void respondThrowsAfterTaking() {
        shortCircuitWith(forbiddenWith(body));
        ScriptedHttpExchange ex = exchange(Behaviour.THROW_AFTER_TAKING, Behaviour.ACCEPT);

        ingress.handle(ex);

        assertThat(body.engineCloses()).isEqualTo(1);
        assertThat(ex.handed().get(1).status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("R5: a short-circuit response without a body is handed over and nothing is closed")
    void nullBody() {
        shortCircuitWith(forbiddenWith(null));
        ScriptedHttpExchange ex = exchange(Behaviour.ACCEPT);

        ingress.handle(ex);

        assertThat(ex.handed()).hasSize(1);
        assertThat(ex.handed().get(0).status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body.libraryCloses()).isZero();
        assertThat(body.engineCloses()).isZero();
    }

    @Test
    @DisplayName("R5: a bodyless short-circuit response whose hand-over fails does not throw out of the gateway")
    void nullBodyHandOverFails() {
        shortCircuitWith(forbiddenWith(null));
        ScriptedHttpExchange ex = exchange(Behaviour.THROW_BEFORE_TAKING, Behaviour.ACCEPT);

        ingress.handle(ex);

        assertThat(ex.handed()).hasSize(2);
        assertThat(ex.handed().get(1).status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body.libraryCloses()).isZero();
    }
}
