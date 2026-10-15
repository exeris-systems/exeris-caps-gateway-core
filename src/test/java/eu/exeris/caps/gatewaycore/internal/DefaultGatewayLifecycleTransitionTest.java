package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycleHook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultGatewayLifecycle: transitions")
class DefaultGatewayLifecycleTransitionTest {

    private static DefaultGatewayLifecycle recording(List<String> events) {
        DefaultGatewayLifecycle lifecycle = new DefaultGatewayLifecycle();
        lifecycle.registerHook(new GatewayLifecycleHook() {
            @Override
            public void onGatewayReady() {
                events.add("ready");
            }

            @Override
            public void onGatewayDrain(Duration remaining) {
                events.add("drain");
            }

            @Override
            public void onGatewayTerminate() {
                events.add("term");
            }
        });
        return lifecycle;
    }

    @Test
    @DisplayName("ready is refused while draining and does not accept traffic again")
    void readyRefusedWhileDraining() throws Exception {
        List<String> events = new ArrayList<>();
        DefaultGatewayLifecycle lifecycle = recording(events);
        lifecycle.initialize();
        lifecycle.ready();
        lifecycle.drain(Duration.ofSeconds(1));

        assertThatThrownBy(lifecycle::ready)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRAINING");
        assertThat(lifecycle.state()).isEqualTo(GatewayLifecycle.State.DRAINING);
        assertThat(lifecycle.isAcceptingTraffic()).isFalse();
        assertThat(events).containsExactly("ready", "drain");
    }

    @Test
    @DisplayName("drain after terminate leaves the gateway terminated and runs no hook")
    void drainAfterTerminateIsNoOp() throws Exception {
        List<String> events = new ArrayList<>();
        DefaultGatewayLifecycle lifecycle = recording(events);
        lifecycle.initialize();
        lifecycle.ready();
        lifecycle.terminate();

        lifecycle.drain(Duration.ofSeconds(1));

        assertThat(lifecycle.state()).isEqualTo(GatewayLifecycle.State.TERMINATED);
        assertThat(events).containsExactly("ready", "term");
        assertThatThrownBy(lifecycle::ready).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("drain and terminate run their hooks once however often they are replayed")
    void replayRunsHooksOnce() throws Exception {
        List<String> events = new ArrayList<>();
        DefaultGatewayLifecycle lifecycle = recording(events);
        lifecycle.initialize();
        lifecycle.ready();
        lifecycle.ready();
        lifecycle.drain(Duration.ofSeconds(1));
        lifecycle.drain(Duration.ofSeconds(1));
        lifecycle.terminate();
        lifecycle.terminate();

        assertThat(events).containsExactly("ready", "drain", "term");
    }

    @Test
    @DisplayName("concurrent drain and terminate end terminated, with each hook run at most once")
    void concurrentDrainAndTerminate() throws Exception {
        for (int round = 0; round < 200; round++) {
            List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
            DefaultGatewayLifecycle lifecycle = recording(events);
            lifecycle.initialize();
            lifecycle.ready();

            Thread drain = Thread.ofPlatform().start(() -> {
                try {
                    lifecycle.drain(Duration.ofSeconds(1));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread terminate = Thread.ofPlatform().start(lifecycle::terminate);
            drain.join();
            terminate.join();

            assertThat(lifecycle.state()).isEqualTo(GatewayLifecycle.State.TERMINATED);
            assertThat(events.stream().filter("term"::equals).count()).isEqualTo(1);
            assertThat(events.stream().filter("drain"::equals).count()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("ready hooks run before the gateway accepts traffic")
    void readyHooksRunBeforeTraffic() throws Exception {
        DefaultGatewayLifecycle lifecycle = new DefaultGatewayLifecycle();
        List<Boolean> acceptingDuringHook = new ArrayList<>();
        lifecycle.registerHook(new GatewayLifecycleHook() {
            @Override
            public void onGatewayReady() {
                acceptingDuringHook.add(lifecycle.isAcceptingTraffic());
            }
        });
        lifecycle.initialize();
        lifecycle.ready();

        assertThat(acceptingDuringHook).containsExactly(false);
        assertThat(lifecycle.isAcceptingTraffic()).isTrue();
        assertThat(lifecycle.readySince()).isNotNull();
    }

    @Test
    @DisplayName("a failing ready hook leaves the gateway not accepting traffic")
    void failingReadyHookKeepsGatewayClosed() throws Exception {
        DefaultGatewayLifecycle lifecycle = new DefaultGatewayLifecycle();
        lifecycle.registerHook(new GatewayLifecycleHook() {
            @Override
            public void onGatewayReady() throws Exception {
                throw new IllegalStateException("not ready");
            }
        });
        lifecycle.initialize();

        assertThatThrownBy(lifecycle::ready).hasMessage("not ready");
        assertThat(lifecycle.isAcceptingTraffic()).isFalse();
        assertThat(lifecycle.state()).isEqualTo(GatewayLifecycle.State.INITIALIZING);
        assertThat(lifecycle.readySince()).isNull();
    }

    @Test
    @DisplayName("a failing initialize hook is fail-stop: replay re-throws and ready is refused")
    void failingInitializeHookIsFailStop() throws Exception {
        DefaultGatewayLifecycle lifecycle = new DefaultGatewayLifecycle();
        List<String> events = new ArrayList<>();
        lifecycle.registerHook(new GatewayLifecycleHook() {
            @Override
            public void onGatewayInitialize() throws Exception {
                throw new java.io.IOException("boom");
            }

            @Override
            public void onGatewayReady() {
                events.add("ready");
            }
        });

        assertThatThrownBy(lifecycle::initialize).isInstanceOf(java.io.IOException.class).hasMessage("boom");
        assertThatThrownBy(lifecycle::initialize).isInstanceOf(java.io.IOException.class).hasMessage("boom");
        assertThatThrownBy(lifecycle::ready)
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(java.io.IOException.class);
        assertThat(lifecycle.isAcceptingTraffic()).isFalse();
        assertThat(events).isEmpty();

        lifecycle.terminate();
        assertThat(lifecycle.state()).isEqualTo(GatewayLifecycle.State.TERMINATED);
    }
}
