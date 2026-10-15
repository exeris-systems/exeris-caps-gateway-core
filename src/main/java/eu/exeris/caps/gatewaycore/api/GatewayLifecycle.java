package eu.exeris.caps.gatewaycore.api;

import eu.exeris.sdk.composition.lifecycle.CapabilityLifecycleHooks;

import java.time.Instant;
import java.util.List;

/**
 * Lifecycle and state controller for the Exeris Gateway runtime.
 *
 * <p>Integrates with the platform's four-phase lifecycle:
 * {@code initialize} -> {@code ready} -> {@code drain} -> {@code terminate}.
 *
 * @since 0.1.0
 */
public interface GatewayLifecycle extends CapabilityLifecycleHooks {

    /**
     * Operational state of the Gateway.
     */
    enum State {
        NEW,
        INITIALIZING,
        READY,
        DRAINING,
        TERMINATED
    }

    /**
     * Current operational state.
     *
     * @return current lifecycle state; never {@code null}
     */
    State state();

    /**
     * Timestamp when the Gateway reached the {@link State#READY} state, or {@code null} if not ready.
     *
     * @return ready timestamp or {@code null}
     */
    Instant readySince();

    /**
     * Total number of ingress requests served since reaching {@link State#READY}.
     *
     * @return served request count
     */
    long servedRequestCount();

    /**
     * Returns whether the gateway is in the {@link State#READY} state and accepting traffic.
     *
     * @return {@code true} if accepting traffic
     */
    boolean isAcceptingTraffic();

    /**
     * Registers a lifecycle hook.
     *
     * @param hook the hook to register; non-null
     */
    void registerHook(GatewayLifecycleHook hook);

    /**
     * Returns an unmodifiable snapshot of registered lifecycle hooks.
     *
     * @return list of registered hooks
     */
    List<GatewayLifecycleHook> registeredHooks();
}
