package eu.exeris.caps.gatewaycore;

import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.caps.gatewaycore.internal.DefaultGatewayLifecycle;
import eu.exeris.caps.gatewaycore.internal.DefaultIngressBootstrap;
import eu.exeris.sdk.annotation.capability.CapabilityLifecycle;
import eu.exeris.sdk.annotation.capability.CapabilityModule;
import eu.exeris.sdk.annotation.capability.Provides;
import eu.exeris.sdk.composition.lifecycle.CapabilityLifecycleHooks;

import java.time.Duration;
import java.util.Objects;

/**
 * The capability declaration and lifecycle owner for {@code exeris-caps-gateway-core}.
 *
 * <p>Provides the substrate foundation for all Gateway capabilities and SKUs:
 * <ul>
 *   <li>{@link GatewayLifecycle} for lifecycle coordination and readiness;</li>
 *   <li>{@link IngressBootstrap} for kernel HTTP ingress dispatch;</li>
 *   <li>{@link PolicyChain} for pre-routing and post-routing policy pipeline execution.</li>
 * </ul>
 *
 * <p>Annotated with {@code @CapabilityLifecycle} and implements {@link CapabilityLifecycleHooks}
 * to coordinate the gateway lifecycle with the composition runtime.
 *
 * @since 0.1.0
 */
@CapabilityModule
@CapabilityLifecycle
@Provides(service = GatewayLifecycle.class, version = "0.1.0")
@Provides(service = IngressBootstrap.class, version = "0.1.0")
@Provides(service = PolicyChain.class, version = "0.1.0")
public final class GatewayCoreModule implements CapabilityLifecycleHooks {

    private final DefaultGatewayLifecycle gatewayLifecycle;
    private final IngressBootstrap ingressBootstrap;

    /**
     * Public no-arg constructor for reflective instantiation by the composition runtime.
     */
    public GatewayCoreModule() {
        this(new DefaultGatewayLifecycle());
    }

    public GatewayCoreModule(DefaultGatewayLifecycle gatewayLifecycle) {
        this.gatewayLifecycle = Objects.requireNonNull(gatewayLifecycle, "gatewayLifecycle");
        this.ingressBootstrap = new DefaultIngressBootstrap(gatewayLifecycle);
    }

    public GatewayCoreModule(DefaultGatewayLifecycle gatewayLifecycle, IngressBootstrap ingressBootstrap) {
        this.gatewayLifecycle = Objects.requireNonNull(gatewayLifecycle, "gatewayLifecycle");
        this.ingressBootstrap = Objects.requireNonNull(ingressBootstrap, "ingressBootstrap");
    }

    public GatewayLifecycle gatewayLifecycle() {
        return gatewayLifecycle;
    }

    public IngressBootstrap ingressBootstrap() {
        return ingressBootstrap;
    }

    public PolicyChain policyChain() {
        return ingressBootstrap.policyChain();
    }

    @Override
    public void initialize() throws Exception {
        gatewayLifecycle.initialize();
    }

    @Override
    public void ready() throws Exception {
        gatewayLifecycle.ready();
    }

    @Override
    public void drain(Duration remaining) throws Exception {
        gatewayLifecycle.drain(remaining);
    }

    @Override
    public void terminate() {
        gatewayLifecycle.terminate();
    }
}
