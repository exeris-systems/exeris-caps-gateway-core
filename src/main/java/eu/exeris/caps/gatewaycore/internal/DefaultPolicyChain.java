package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standard implementation of {@link PolicyChain}.
 *
 * <p>Uses atomic array updates on registration to ensure lock-free, zero-allocation
 * iteration on the high-throughput request hot path.
 *
 * @since 0.1.0
 */
public final class DefaultPolicyChain implements PolicyChain {

    private static final Comparator<GatewayPolicy> POLICY_COMPARATOR =
            Comparator.comparingInt(GatewayPolicy::order).thenComparing(GatewayPolicy::name);

    private final AtomicReference<List<GatewayPolicy>> policiesRef = new AtomicReference<>(List.of());

    @Override
    public synchronized void addPolicy(GatewayPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        List<GatewayPolicy> current = new ArrayList<>(policiesRef.get());
        // Remove existing if matching name
        current.removeIf(p -> p.name().equals(policy.name()));
        current.add(policy);
        current.sort(POLICY_COMPARATOR);
        policiesRef.set(List.copyOf(current));
    }

    @Override
    public synchronized boolean removePolicy(String name) {
        Objects.requireNonNull(name, "name");
        List<GatewayPolicy> current = new ArrayList<>(policiesRef.get());
        boolean removed = current.removeIf(p -> p.name().equals(name));
        if (removed) {
            policiesRef.set(List.copyOf(current));
        }
        return removed;
    }

    @Override
    public List<GatewayPolicy> policies() {
        return policiesRef.get();
    }

    @Override
    public PolicyResult executePreRouting(HttpExchange exchange) {
        Objects.requireNonNull(exchange, "exchange");
        List<GatewayPolicy> policies = policiesRef.get();
        // Index-based iteration to avoid iterator allocation
        int size = policies.size();
        for (int i = 0; i < size; i++) {
            GatewayPolicy policy = policies.get(i);
            PolicyResult result = policy.preRouting(exchange);
            if (result != null && result.isShortCircuit()) {
                return result;
            }
        }
        return PolicyResult.proceed();
    }

    @Override
    public HttpResponse executePostRouting(HttpExchange exchange, HttpResponse response) {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(response, "response");
        List<GatewayPolicy> policies = policiesRef.get();
        HttpResponse current = response;
        // Reverse order (onion pattern)
        for (int i = policies.size() - 1; i >= 0; i--) {
            GatewayPolicy policy = policies.get(i);
            HttpResponse updated = policy.postRouting(exchange, current);
            if (updated != null) {
                current = updated;
            }
        }
        return current;
    }
}
