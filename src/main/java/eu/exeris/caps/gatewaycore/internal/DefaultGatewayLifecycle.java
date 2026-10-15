package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycleHook;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Standard implementation of {@link GatewayLifecycle}.
 *
 * <p>Coordinates the four-phase lifecycle with thread-safe atomic state transitions
 * and dispatches events to registered {@link GatewayLifecycleHook} instances.
 *
 * @since 0.1.0
 */
public final class DefaultGatewayLifecycle implements GatewayLifecycle {

    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final List<GatewayLifecycleHook> hooks = new CopyOnWriteArrayList<>();
    private final LongAdder servedRequests = new LongAdder();
    private volatile Instant readySince;

    @Override
    public State state() {
        return state.get();
    }

    @Override
    public Instant readySince() {
        return readySince;
    }

    @Override
    public long servedRequestCount() {
        return servedRequests.sum();
    }

    @Override
    public boolean isAcceptingTraffic() {
        return state.get() == State.READY;
    }

    @Override
    public void registerHook(GatewayLifecycleHook hook) {
        Objects.requireNonNull(hook, "hook");
        hooks.add(hook);
    }

    @Override
    public List<GatewayLifecycleHook> registeredHooks() {
        return Collections.unmodifiableList(new ArrayList<>(hooks));
    }

    public void incrementServedRequests() {
        servedRequests.increment();
    }

    @Override
    public void initialize() throws Exception {
        if (!state.compareAndSet(State.NEW, State.INITIALIZING)) {
            // Idempotent replay support per ADR-024
            return;
        }

        for (GatewayLifecycleHook hook : hooks) {
            hook.onGatewayInitialize();
        }
    }

    @Override
    public void ready() throws Exception {
        while (true) {
            State current = state.get();
            if (current == State.READY) {
                // Idempotent replay: hooks already ran for this transition
                return;
            }
            if (current == State.DRAINING || current == State.TERMINATED) {
                throw new IllegalStateException("Cannot transition to READY from " + current + " state");
            }
            if (state.compareAndSet(current, State.READY)) {
                break;
            }
        }
        readySince = Instant.now();

        for (GatewayLifecycleHook hook : hooks) {
            hook.onGatewayReady();
        }
    }

    @Override
    public void drain(Duration remaining) throws Exception {
        while (true) {
            State current = state.get();
            if (current == State.DRAINING || current == State.TERMINATED) {
                // Draining twice, or after terminate, does not move the state or re-run hooks
                return;
            }
            if (state.compareAndSet(current, State.DRAINING)) {
                break;
            }
        }

        List<GatewayLifecycleHook> reversed = new ArrayList<>(hooks);
        Collections.reverse(reversed);
        for (GatewayLifecycleHook hook : reversed) {
            hook.onGatewayDrain(remaining);
        }
    }

    @Override
    public void terminate() {
        if (state.getAndSet(State.TERMINATED) == State.TERMINATED) {
            return;
        }

        List<GatewayLifecycleHook> reversed = new ArrayList<>(hooks);
        Collections.reverse(reversed);
        for (GatewayLifecycleHook hook : reversed) {
            try {
                hook.onGatewayTerminate();
            } catch (Exception ignored) {
                // Terminate is unconditional and best-effort per ADR-024
            }
        }
    }
}
