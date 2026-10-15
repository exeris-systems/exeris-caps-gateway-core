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
    private volatile Exception initializeFailure;

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
            // Idempotent replay per ADR-024; a failed first run is re-thrown, never swallowed
            Exception failure = initializeFailure;
            if (failure != null) {
                throw failure;
            }
            return;
        }

        try {
            for (GatewayLifecycleHook hook : hooks) {
                hook.onGatewayInitialize();
            }
        } catch (Exception e) {
            // Fail-stop: the state stays INITIALIZING (never READY), so no traffic is accepted
            initializeFailure = e;
            throw e;
        }
    }

    @Override
    public synchronized void ready() throws Exception {
        State current = state.get();
        if (current == State.READY) {
            // Idempotent replay: hooks already ran for this transition
            return;
        }
        if (current == State.DRAINING || current == State.TERMINATED) {
            throw new IllegalStateException("Cannot transition to READY from " + current + " state");
        }
        Exception failure = initializeFailure;
        if (failure != null) {
            throw new IllegalStateException("Cannot transition to READY: initialize failed", failure);
        }

        // Hooks complete before the transition, so traffic is accepted only once they have all returned
        for (GatewayLifecycleHook hook : hooks) {
            hook.onGatewayReady();
        }

        // A drain or terminate that raced the hooks wins; never move out of it back to READY
        if (!state.compareAndSet(current, State.READY)) {
            State now = state.get();
            if (now == State.READY) {
                return;
            }
            throw new IllegalStateException("Cannot transition to READY from " + now + " state");
        }
        readySince = Instant.now();
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
