package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpHandler;
import eu.exeris.kernel.spi.http.HttpStatus;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standard implementation of {@link IngressBootstrap}.
 *
 * <p>Validates gateway readiness against {@link GatewayLifecycle}, increments traffic metrics,
 * executes pre-routing and post-routing {@link PolicyChain} policies, and routes inbound requests
 * to prefix handlers or downstream delegates.
 *
 * @since 0.1.0
 */
public final class DefaultIngressBootstrap implements IngressBootstrap {

    private static final System.Logger LOG = System.getLogger(DefaultIngressBootstrap.class.getName());

    private final DefaultGatewayLifecycle gatewayLifecycle;
    private final PolicyChain policyChain;
    private final ConcurrentMap<String, HttpHandler> prefixHandlers = new ConcurrentHashMap<>();
    private final AtomicReference<HttpHandler> delegateHandler = new AtomicReference<>();

    public DefaultIngressBootstrap(DefaultGatewayLifecycle gatewayLifecycle) {
        this(gatewayLifecycle, new DefaultPolicyChain());
    }

    public DefaultIngressBootstrap(DefaultGatewayLifecycle gatewayLifecycle, PolicyChain policyChain) {
        this.gatewayLifecycle = Objects.requireNonNull(gatewayLifecycle, "gatewayLifecycle");
        this.policyChain = Objects.requireNonNull(policyChain, "policyChain");
    }

    @Override
    public GatewayLifecycle gatewayLifecycle() {
        return gatewayLifecycle;
    }

    @Override
    public PolicyChain policyChain() {
        return policyChain;
    }

    @Override
    public void registerHandler(String pathPrefix, HttpHandler handler) {
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(handler, "handler");
        prefixHandlers.put(pathPrefix, handler);
    }

    @Override
    public boolean unregisterHandler(String pathPrefix) {
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        return prefixHandlers.remove(pathPrefix) != null;
    }

    @Override
    public void setDelegateHandler(HttpHandler delegate) {
        delegateHandler.set(delegate);
    }

    @Override
    public HttpHandler delegateHandler() {
        return delegateHandler.get();
    }

    @Override
    public Map<String, HttpHandler> registeredHandlers() {
        return Collections.unmodifiableMap(prefixHandlers);
    }

    @Override
    public void handle(HttpExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");

        if (!gatewayLifecycle.isAcceptingTraffic()) {
            exchange.respond(HttpStatus.SERVICE_UNAVAILABLE);
            return;
        }

        gatewayLifecycle.incrementServedRequests();

        try {
            // 1. Evaluate pre-routing policies (e.g. CORS preflight, RateLimiting, Auth)
            PolicyResult preResult = policyChain.executePreRouting(exchange);
            if (preResult != null && preResult.isShortCircuit()) {
                exchange.respond(preResult.response());
                return;
            }

            // 2. Wrap exchange if policies are registered to intercept response for post-routing
            HttpExchange effectiveExchange = policyChain.policies().isEmpty()
                    ? exchange
                    : new PolicyInterceptingHttpExchange(exchange, policyChain);

            // 3. Longest prefix matching
            String path = exchange.request().path();
            HttpHandler matched = null;
            int longestMatch = -1;

            for (Map.Entry<String, HttpHandler> entry : prefixHandlers.entrySet()) {
                String prefix = entry.getKey();
                if (matchesPrefix(path, prefix) && prefix.length() > longestMatch) {
                    matched = entry.getValue();
                    longestMatch = prefix.length();
                }
            }

            if (matched != null) {
                matched.handle(effectiveExchange);
                return;
            }

            HttpHandler delegate = delegateHandler.get();
            if (delegate != null) {
                delegate.handle(effectiveExchange);
                return;
            }

            effectiveExchange.respond(HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Request failed in the ingress pipeline: {0}", e.toString());
            try {
                exchange.respond(HttpStatus.INTERNAL_SERVER_ERROR);
            } catch (Exception ignored) {
                // Best-effort failure response
            }
        }
    }

    /**
     * A prefix matches a path only at a segment boundary: {@code /api} matches {@code /api} and
     * {@code /api/users}, not {@code /apix}. A prefix ending in {@code /} already ends on a boundary.
     */
    private static boolean matchesPrefix(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        return path.length() == prefix.length()
                || prefix.endsWith("/")
                || path.charAt(prefix.length()) == '/';
    }
}
