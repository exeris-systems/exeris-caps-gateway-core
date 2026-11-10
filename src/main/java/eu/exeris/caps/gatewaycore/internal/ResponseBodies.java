package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * Response-body ownership rules shared by the ingress pipeline.
 *
 * <p>The caller owns a response body until {@link HttpExchange#respond(HttpResponse)} returns
 * normally; from then on the engine owns it and releases it exactly once. A body whose hand-over
 * did not complete is still the caller's, and this class is the one place that releases it.
 * {@link LoanedBuffer#close()} is idempotent, so releasing a body the engine already released
 * while failing is a no-op rather than a second release.
 */
final class ResponseBodies {

    private ResponseBodies() {
    }

    /**
     * Hands {@code response} to {@code exchange}. On a normal return the engine owns the body and
     * this class never touches it again; if {@code respond} throws, the body is released here and
     * the failure is rethrown unchanged.
     */
    static void respond(HttpExchange exchange, HttpResponse response) {
        try {
            exchange.respond(response);
        } catch (Throwable failure) {
            release(response, failure);
            throw failure;
        }
    }

    /**
     * Releases the body of {@code response}, if any. A failure of the release is attached to
     * {@code cause} as suppressed and never replaces it.
     */
    static void release(HttpResponse response, Throwable cause) {
        if (response == null) {
            return;
        }
        LoanedBuffer body = response.body();
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (RuntimeException closeFailure) {
            cause.addSuppressed(closeFailure);
        }
    }

    /**
     * Releases the body of a response that is being replaced. A failing release is ignored so the
     * replacement can still be handed over and released in its turn.
     */
    static void discard(HttpResponse response) {
        release(response, new Throwable());
    }
}
