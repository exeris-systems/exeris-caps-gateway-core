package eu.exeris.caps.gatewaycore.api;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpResponse;

import java.util.List;

/**
 * Coordinated execution pipeline for {@link GatewayPolicy} policies.
 *
 * <p>Manages policy registration, deterministic ordering, and pipeline execution
 * for pre-routing and post-routing stages.
 *
 * @since 0.1.0
 */
public interface PolicyChain {

    /**
     * Registers a policy into the chain.
     *
     * @param policy the policy to add; non-null
     */
    void addPolicy(GatewayPolicy policy);

    /**
     * Removes a policy from the chain by name.
     *
     * @param name the policy name; non-null
     * @return {@code true} if removed, {@code false} if not found
     */
    boolean removePolicy(String name);

    /**
     * Returns an unmodifiable snapshot of registered policies in execution order.
     *
     * @return ordered list of policies
     */
    List<GatewayPolicy> policies();

    /**
     * Executes the pre-routing phase across registered policies.
     *
     * <p>Policies execute in ascending {@link GatewayPolicy#order()}. If any policy returns
     * {@link PolicyResult#shortCircuit(HttpResponse)}, execution terminates immediately
     * and that result is returned.
     *
     * @param exchange incoming exchange; non-null
     * @return {@link PolicyResult#proceed()} if all policies permitted, or the short-circuit result
     */
    PolicyResult executePreRouting(HttpExchange exchange);

    /**
     * Executes the post-routing phase across registered policies.
     *
     * <p>Policies execute in descending order of their pre-routing sequence (onion pattern),
     * allowing each policy to augment or transform the response.
     *
     * @param exchange active exchange; non-null
     * @param response the upstream response; non-null
     * @return final transformed response to send downstream; non-null
     */
    HttpResponse executePostRouting(HttpExchange exchange, HttpResponse response);
}
