package eu.exeris.caps.gatewaycore.api;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;

import java.util.Map;

/**
 * The primary ingress entry point for the Gateway runtime.
 *
 * <p>Implements {@link HttpHandler} to receive inbound {@link HttpExchange} requests from
 * the Exeris kernel HTTP transport engine, coordinating lifecycle gating, traffic accounting,
 * and dispatching to registered route handlers or downstream capability surfaces (such as
 * {@code ApiSurfaceRegistry}).
 *
 * @since 0.1.0
 */
public interface IngressBootstrap extends HttpHandler {

    /**
     * Gateway lifecycle state controller.
     *
     * @return lifecycle controller; never {@code null}
     */
    GatewayLifecycle gatewayLifecycle();

    /**
     * Gateway policy pipeline.
     *
     * @return policy chain controller; never {@code null}
     */
    PolicyChain policyChain();

    /**
     * Registers a path-prefix handler.
     *
     * @param pathPrefix the URI path prefix (e.g. "/api"); non-null
     * @param handler    the HTTP handler to dispatch matching requests to; non-null
     */
    void registerHandler(String pathPrefix, HttpHandler handler);

    /**
     * Unregisters a path-prefix handler.
     *
     * @param pathPrefix the URI path prefix; non-null
     * @return {@code true} if removed, {@code false} otherwise
     */
    boolean unregisterHandler(String pathPrefix);

    /**
     * Sets a fallback or downstream delegate HTTP handler.
     *
     * @param delegate the delegate handler, or {@code null} to clear
     */
    void setDelegateHandler(HttpHandler delegate);

    /**
     * Returns the current delegate HTTP handler, if configured.
     *
     * @return delegate handler, or {@code null}
     */
    HttpHandler delegateHandler();

    /**
     * Returns an unmodifiable snapshot of registered path-prefix handlers.
     *
     * @return map of path prefixes to handlers
     */
    Map<String, HttpHandler> registeredHandlers();

    /**
     * Handles an inbound HTTP exchange from the kernel transport engine.
     *
     * @param exchange the incoming exchange; non-null
     */
    @Override
    void handle(HttpExchange exchange);
}
