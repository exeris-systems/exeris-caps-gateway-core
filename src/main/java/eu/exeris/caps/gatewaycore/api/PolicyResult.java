package eu.exeris.caps.gatewaycore.api;

import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;

import java.util.List;
import java.util.Objects;

/**
 * Result of evaluating a {@link GatewayPolicy} during the pre-routing phase.
 *
 * <p>A policy may either allow execution to {@link #proceed()}, or {@link #shortCircuit(HttpResponse)}
 * to terminate request processing immediately on the gateway edge without forwarding to upstream
 * handlers.
 *
 * @since 0.1.0
 */
public final class PolicyResult {

    private static final PolicyResult PROCEED = new PolicyResult(false, null);

    private final boolean shortCircuit;
    private final HttpResponse response;

    private PolicyResult(boolean shortCircuit, HttpResponse response) {
        this.shortCircuit = shortCircuit;
        this.response = response;
    }

    /**
     * Signals that the policy allows the exchange to proceed to the next policy or target handler.
     *
     * @return proceed result singleton
     */
    public static PolicyResult proceed() {
        return PROCEED;
    }

    /**
     * Signals that the policy short-circuits the exchange and immediately responds to downstream.
     *
     * @param response the response to send downstream; non-null
     * @return short-circuit policy result
     */
    public static PolicyResult shortCircuit(HttpResponse response) {
        Objects.requireNonNull(response, "response");
        return new PolicyResult(true, response);
    }

    /**
     * Convenience method to short-circuit with an empty response of the specified status.
     *
     * @param status the HTTP status to respond with; non-null
     * @return short-circuit policy result
     */
    public static PolicyResult shortCircuit(HttpStatus status) {
        Objects.requireNonNull(status, "status");
        return shortCircuit(new HttpResponse(status, HttpVersion.HTTP_1_1, List.of(), null));
    }

    /**
     * Whether this policy result instructs the gateway to short-circuit the request.
     *
     * @return {@code true} if short-circuiting, {@code false} if proceeding
     */
    public boolean isShortCircuit() {
        return shortCircuit;
    }

    /**
     * The response to send if short-circuiting, or {@code null} if proceeding.
     *
     * @return the short-circuit response or {@code null}
     */
    public HttpResponse response() {
        return response;
    }
}
