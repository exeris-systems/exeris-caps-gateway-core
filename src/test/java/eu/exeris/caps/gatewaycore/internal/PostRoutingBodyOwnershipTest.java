package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.testutil.CountingBody;
import eu.exeris.caps.gatewaycore.testutil.ScriptedHttpExchange;
import eu.exeris.caps.gatewaycore.testutil.ScriptedHttpExchange.Behaviour;
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

@DisplayName("Post-routing response body ownership")
class PostRoutingBodyOwnershipTest {

    private DefaultPolicyChain chain;
    private CountingBody original;
    private CountingBody replacement;
    private ScriptedHttpExchange delegate;

    @BeforeEach
    void setUp() {
        chain = new DefaultPolicyChain();
        original = new CountingBody();
        replacement = new CountingBody();
        delegate = new ScriptedHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/x", HttpVersion.HTTP_1_1, List.of()), original, replacement);
    }

    private HttpResponse response(HttpStatus status, CountingBody body) {
        return new HttpResponse(status, HttpVersion.HTTP_1_1, List.of(), body == null ? null : body.buffer());
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

    private PolicyInterceptingHttpExchange intercepting() {
        return new PolicyInterceptingHttpExchange(delegate, chain);
    }

    // ---- R3 ----

    @Test
    @DisplayName("R3: a throwing policy makes the chain release the body in hand once and rethrow the same failure")
    void chainReleasesWhenPolicyThrows() {
        RuntimeException boom = new RuntimeException("policy failed");
        policy("p", 0, (e, r) -> {
            throw boom;
        });

        assertThatThrownBy(() -> chain.executePostRouting(delegate, response(HttpStatus.OK, original)))
                .isSameAs(boom);

        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(original.engineCloses()).isZero();
    }

    @Test
    @DisplayName("R3: through the exchange a throwing policy releases the body once and nothing reaches the delegate")
    void exchangeReleasesWhenPolicyThrows() {
        policy("p", 0, (e, r) -> {
            throw new IllegalArgumentException("policy failed");
        });

        assertThatThrownBy(() -> intercepting().respond(response(HttpStatus.OK, original)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(delegate.handed()).isEmpty();
        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(original.engineCloses()).isZero();
    }

    @Test
    @DisplayName("R3: a policy that throws after another replaced the body releases both bodies once each")
    void throwAfterReplacement() {
        // post-routing runs in reverse order: "late" (order 2) first, then "early" (order 1)
        policy("late", 2, (e, r) -> response(HttpStatus.OK, replacement));
        policy("early", 1, (e, r) -> {
            throw new IllegalStateException("policy failed");
        });

        assertThatThrownBy(() -> chain.executePostRouting(delegate, response(HttpStatus.OK, original)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(replacement.libraryCloses()).isEqualTo(1);
        assertThat(original.engineCloses()).isZero();
        assertThat(replacement.engineCloses()).isZero();
    }

    // ---- R4 ----

    @Test
    @DisplayName("R4: a replacement with a different body releases the original once and leaves the replacement untouched")
    void chainReplacesBody() {
        HttpResponse replaced = response(HttpStatus.FORBIDDEN, replacement);
        policy("p", 0, (e, r) -> replaced);

        HttpResponse result = chain.executePostRouting(delegate, response(HttpStatus.OK, original));

        assertThat(result).isSameAs(replaced);
        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(replacement.libraryCloses()).isZero();
        assertThat(replacement.engineCloses()).isZero();
    }

    @Test
    @DisplayName("R4/R1: the replacement body is handed over and released once by the engine only")
    void replacementHandedOver() {
        policy("p", 0, (e, r) -> response(HttpStatus.FORBIDDEN, replacement));

        intercepting().respond(response(HttpStatus.OK, original));

        assertThat(delegate.handed()).hasSize(1);
        assertThat(delegate.handed().get(0).body()).isSameAs(replacement.buffer());
        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(original.engineCloses()).isZero();
        assertThat(replacement.libraryCloses()).isZero();
        assertThat(replacement.engineCloses()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4/R2: when the delegate refuses the replacement both bodies are released once by the gateway")
    void replacementRefused() {
        delegate.script(Behaviour.THROW_BEFORE_TAKING);
        policy("p", 0, (e, r) -> response(HttpStatus.FORBIDDEN, replacement));

        assertThatThrownBy(() -> intercepting().respond(response(HttpStatus.OK, original)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(replacement.libraryCloses()).isEqualTo(1);
        assertThat(original.engineCloses()).isZero();
        assertThat(replacement.engineCloses()).isZero();
    }

    @Test
    @DisplayName("R4: replacing a body with a bodyless response releases the original once")
    void replacementWithoutBody() {
        policy("p", 0, (e, r) -> response(HttpStatus.NO_CONTENT, null));

        intercepting().respond(response(HttpStatus.OK, original));

        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(original.engineCloses()).isZero();
        assertThat(delegate.handed()).hasSize(1);
        assertThat(delegate.handed().get(0).body()).isNull();
    }

    @Test
    @DisplayName("R4: a chain of two replacements releases each superseded body once")
    void twoReplacements() {
        CountingBody third = new CountingBody();
        delegate.know(third);
        policy("late", 2, (e, r) -> response(HttpStatus.FORBIDDEN, replacement));
        policy("early", 1, (e, r) -> response(HttpStatus.BAD_GATEWAY, third));

        intercepting().respond(response(HttpStatus.OK, original));

        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(replacement.libraryCloses()).isEqualTo(1);
        assertThat(third.libraryCloses()).isZero();
        assertThat(third.engineCloses()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4: a response sharing the same body instance keeps it unreleased until the engine takes it")
    void sameBodyPassThrough() {
        policy("p", 0, (e, r) -> response(HttpStatus.ACCEPTED, original));

        HttpResponse result = chain.executePostRouting(delegate, response(HttpStatus.OK, original));

        assertThat(result.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(result.body()).isSameAs(original.buffer());
        assertThat(original.libraryCloses()).isZero();
        assertThat(original.engineCloses()).isZero();
    }

    @Test
    @DisplayName("R4/R1: a same-body response is handed over and released once by the engine only")
    void sameBodyHandedOver() {
        policy("p", 0, (e, r) -> response(HttpStatus.ACCEPTED, original));

        intercepting().respond(response(HttpStatus.OK, original));

        assertThat(delegate.handed()).hasSize(1);
        assertThat(original.libraryCloses()).isZero();
        assertThat(original.engineCloses()).isEqualTo(1);
    }

    @Test
    @DisplayName("R4: a policy returning null keeps the response in hand and releases nothing")
    void nullFromPolicy() {
        policy("p", 0, (e, r) -> null);

        intercepting().respond(response(HttpStatus.OK, original));

        assertThat(original.libraryCloses()).isZero();
        assertThat(original.engineCloses()).isEqualTo(1);
    }

    // ---- hand-over of the final response ----

    @Test
    @DisplayName("R1: with no policies the body is handed over and released once by the engine only")
    void noPolicies() {
        intercepting().respond(response(HttpStatus.OK, original));

        assertThat(original.libraryCloses()).isZero();
        assertThat(original.engineCloses()).isEqualTo(1);
    }

    @Test
    @DisplayName("R2: when the delegate refuses the response the gateway releases the body once and rethrows")
    void delegateRefuses() {
        delegate.script(Behaviour.THROW_BEFORE_TAKING);
        policy("p", 0, (e, r) -> r);

        assertThatThrownBy(() -> intercepting().respond(response(HttpStatus.OK, original)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(original.libraryCloses()).isEqualTo(1);
        assertThat(original.engineCloses()).isZero();
    }

    @Test
    @DisplayName("R2: when the delegate took the body before failing the engine's release stays the only one and the failure propagates")
    void delegateFailsAfterTaking() {
        delegate.script(Behaviour.THROW_AFTER_TAKING);
        policy("p", 0, (e, r) -> r);

        assertThatThrownBy(() -> intercepting().respond(response(HttpStatus.OK, original)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(original.engineCloses()).isEqualTo(1);
    }

    // ---- typed responses ----

    @Test
    @DisplayName("R2: a typed response replaced by a body-carrying response follows the hand-over rules")
    void typedReplacedByBody() {
        delegate.script(Behaviour.THROW_BEFORE_TAKING);
        policy("p", 0, (e, r) -> response(HttpStatus.FORBIDDEN, replacement));

        assertThatThrownBy(() -> intercepting().respond(HttpTypedResponse.of(HttpStatus.OK, "payload")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(replacement.libraryCloses()).isEqualTo(1);
        assertThat(replacement.engineCloses()).isZero();
    }

    // ---- R5 ----

    @Test
    @DisplayName("R5: a bodyless response passes through policies and the exchange with nothing closed")
    void nullBodyEverywhere() {
        policy("late", 2, (e, r) -> r);
        policy("early", 1, (e, r) -> response(HttpStatus.ACCEPTED, null));

        intercepting().respond(response(HttpStatus.OK, null));

        assertThat(delegate.handed()).hasSize(1);
        assertThat(delegate.handed().get(0).status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(original.libraryCloses()).isZero();
        assertThat(replacement.libraryCloses()).isZero();
    }

    @Test
    @DisplayName("R5: a bodyless response with a throwing policy rethrows the failure and closes nothing")
    void nullBodyPolicyThrows() {
        policy("p", 0, (e, r) -> {
            throw new IllegalStateException("policy failed");
        });

        assertThatThrownBy(() -> intercepting().respond(response(HttpStatus.OK, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(original.libraryCloses()).isZero();
    }

    @Test
    @DisplayName("R5: replacing a bodyless response with one carrying a body leaves the new body for the engine")
    void bodylessReplacedByBody() {
        policy("p", 0, (e, r) -> response(HttpStatus.FORBIDDEN, replacement));

        intercepting().respond(response(HttpStatus.OK, null));

        assertThat(replacement.libraryCloses()).isZero();
        assertThat(replacement.engineCloses()).isEqualTo(1);
    }
}
