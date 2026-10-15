package eu.exeris.caps.gatewaycore.api;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpResponse;

/**
 * Contract for policies executed within the {@link PolicyChain}.
 *
 * <p>Policies participate in two pipeline phases:
 * <ul>
 *   <li><b>Pre-routing:</b> Evaluated before request dispatching to an upstream handler.
 *       May permit the request to {@link PolicyResult#proceed()}, or short-circuit with
 *       an immediate {@link PolicyResult#shortCircuit(HttpResponse)} (e.g. CORS preflight 204,
 *       authentication failure 401/403, or rate limit 429).</li>
 *   <li><b>Post-routing:</b> Evaluated on the response returned from upstream before sending
 *       it downstream to the client. May augment response headers (e.g. CORS {@code Access-Control-*},
 *       security headers, or rate limit quota headers).</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface GatewayPolicy {

    /**
     * Unique policy identifier (e.g. "cors-policy", "rate-limiting").
     *
     * @return policy name; non-null
     */
    String name();

    /**
     * Execution order in the policy pipeline. Lower values run earlier in pre-routing
     * and later in post-routing (onion / filter-chain style).
     *
     * @return order index, default 0
     */
    default int order() {
        return 0;
    }

    /**
     * Evaluates policy before routing.
     *
     * @param exchange the incoming HTTP exchange; non-null
     * @return policy result (proceed or short-circuit); non-null
     */
    default PolicyResult preRouting(HttpExchange exchange) {
        return PolicyResult.proceed();
    }

    /**
     * Evaluates policy after receiving an upstream response.
     *
     * @param exchange the active HTTP exchange; non-null
     * @param response the current HTTP response from upstream or previous policies; non-null
     * @return the resulting HTTP response to pass down; non-null
     */
    default HttpResponse postRouting(HttpExchange exchange, HttpResponse response) {
        return response;
    }
}
