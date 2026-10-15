package eu.exeris.caps.gatewaycore.api;

import java.time.Duration;

/**
 * Interceptor hook for gateway lifecycle transitions.
 *
 * <p>Enables Layer 2 gateway building blocks (route registry, upstream pool, policy chain,
 * backend health monitor) and composed capabilities to participate in the four-phase lifecycle
 * governed by {@link GatewayLifecycle}.
 *
 * @since 0.1.0
 */
public interface GatewayLifecycleHook {

    /**
     * Invoked during the {@code initialize} phase in forward registration order.
     *
     * <p>Fail-stop: if a hook throws, the exception propagates from
     * {@link GatewayLifecycle#initialize()}, later hooks do not run, the state stays
     * {@code INITIALIZING} (never {@code READY}), a replayed {@code initialize()} re-throws the
     * same failure, and {@code ready()} is refused. The gateway is then only terminated.
     *
     * @throws Exception if initialization fails
     */
    default void onGatewayInitialize() throws Exception {}

    /**
     * Invoked during the {@code ready} phase in forward registration order.
     *
     * <p>Runs before the transition to {@code READY}: {@code isAcceptingTraffic()} is false until
     * every ready hook has returned. If a hook throws, the state does not change and no traffic is
     * accepted.
     *
     * @throws Exception if readiness verification fails
     */
    default void onGatewayReady() throws Exception {}

    /**
     * Invoked during the {@code drain} phase in reverse registration order.
     *
     * @param remaining remaining time until the drain deadline expires
     * @throws Exception if draining encounters an error
     */
    default void onGatewayDrain(Duration remaining) throws Exception {}

    /**
     * Invoked during the {@code terminate} phase in reverse registration order.
     * Releases owned resources unconditionally; should not throw.
     */
    default void onGatewayTerminate() {}
}
