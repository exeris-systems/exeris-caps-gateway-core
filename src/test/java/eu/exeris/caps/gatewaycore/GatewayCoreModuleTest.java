package eu.exeris.caps.gatewaycore;

import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycleHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GatewayCoreModule")
class GatewayCoreModuleTest {

    @Test
    @DisplayName("instantiates via public no-arg constructor and exposes non-null services")
    void defaultConstructor() {
        GatewayCoreModule module = new GatewayCoreModule();
        assertThat(module.gatewayLifecycle()).isNotNull();
        assertThat(module.ingressBootstrap()).isNotNull();
        assertThat(module.policyChain()).isNotNull();
        assertThat(module.gatewayLifecycle().state()).isEqualTo(GatewayLifecycle.State.NEW);
        assertThat(module.gatewayLifecycle().isAcceptingTraffic()).isFalse();
        assertThat(module.gatewayLifecycle().readySince()).isNull();
        assertThat(module.gatewayLifecycle().servedRequestCount()).isZero();
    }

    @Test
    @DisplayName("coordinates lifecycle phases and invokes hooks in proper order")
    void lifecycleCoordination() throws Exception {
        GatewayCoreModule module = new GatewayCoreModule();
        List<String> events = new ArrayList<>();

        GatewayLifecycleHook hook1 = new GatewayLifecycleHook() {
            @Override
            public void onGatewayInitialize() {
                events.add("hook1:init");
            }

            @Override
            public void onGatewayReady() {
                events.add("hook1:ready");
            }

            @Override
            public void onGatewayDrain(Duration remaining) {
                events.add("hook1:drain:" + remaining.toSeconds());
            }

            @Override
            public void onGatewayTerminate() {
                events.add("hook1:term");
            }
        };

        GatewayLifecycleHook hook2 = new GatewayLifecycleHook() {
            @Override
            public void onGatewayInitialize() {
                events.add("hook2:init");
            }

            @Override
            public void onGatewayReady() {
                events.add("hook2:ready");
            }

            @Override
            public void onGatewayDrain(Duration remaining) {
                events.add("hook2:drain:" + remaining.toSeconds());
            }

            @Override
            public void onGatewayTerminate() {
                events.add("hook2:term");
            }
        };

        module.gatewayLifecycle().registerHook(hook1);
        module.gatewayLifecycle().registerHook(hook2);

        assertThat(module.gatewayLifecycle().registeredHooks()).containsExactly(hook1, hook2);

        // Forward order for init and ready
        module.initialize();
        assertThat(events).containsExactly("hook1:init", "hook2:init");
        assertThat(module.gatewayLifecycle().isAcceptingTraffic()).isFalse();

        module.ready();
        assertThat(events).containsExactly("hook1:init", "hook2:init", "hook1:ready", "hook2:ready");
        assertThat(module.gatewayLifecycle().state()).isEqualTo(GatewayLifecycle.State.READY);
        assertThat(module.gatewayLifecycle().isAcceptingTraffic()).isTrue();
        assertThat(module.gatewayLifecycle().readySince()).isNotNull();

        // Reverse order for drain and terminate
        module.drain(Duration.ofSeconds(20));
        assertThat(events).containsExactly(
                "hook1:init", "hook2:init",
                "hook1:ready", "hook2:ready",
                "hook2:drain:20", "hook1:drain:20"
        );
        assertThat(module.gatewayLifecycle().state()).isEqualTo(GatewayLifecycle.State.DRAINING);
        assertThat(module.gatewayLifecycle().isAcceptingTraffic()).isFalse();

        module.terminate();
        assertThat(events).containsExactly(
                "hook1:init", "hook2:init",
                "hook1:ready", "hook2:ready",
                "hook2:drain:20", "hook1:drain:20",
                "hook2:term", "hook1:term"
        );
        assertThat(module.gatewayLifecycle().state()).isEqualTo(GatewayLifecycle.State.TERMINATED);
        assertThat(module.gatewayLifecycle().isAcceptingTraffic()).isFalse();
    }

    @Test
    @DisplayName("initialize is idempotent and does not re-trigger hooks")
    void idempotentInitialize() throws Exception {
        GatewayCoreModule module = new GatewayCoreModule();
        List<String> events = new ArrayList<>();

        module.gatewayLifecycle().registerHook(new GatewayLifecycleHook() {
            @Override
            public void onGatewayInitialize() {
                events.add("init");
            }
        });

        module.initialize();
        module.initialize();

        assertThat(events).containsExactly("init");
    }

    @Test
    @DisplayName("cannot transition to READY from TERMINATED")
    void invalidTransitionFromTerminated() throws Exception {
        GatewayCoreModule module = new GatewayCoreModule();
        module.initialize();
        module.ready();
        module.drain(Duration.ofSeconds(5));
        module.terminate();

        assertThatThrownBy(module::ready)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot transition to READY from TERMINATED");
    }
}
