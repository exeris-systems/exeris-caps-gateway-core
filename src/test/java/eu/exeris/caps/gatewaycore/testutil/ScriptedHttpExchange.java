package eu.exeris.caps.gatewaycore.testutil;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * An exchange that records every response it is handed and behaves per call as scripted. A call
 * beyond the script is accepted. Accepting a response means the engine takes its body and releases it
 * exactly once through the {@link CountingBody} registered for that buffer.
 */
public final class ScriptedHttpExchange implements HttpExchange {

    /** What one {@code respond} call does. */
    public enum Behaviour {
        /** Takes the response; the engine releases its body once. */
        ACCEPT,
        /** Throws without taking the response; the body stays with the caller. */
        THROW_BEFORE_TAKING,
        /** Takes the response, releases its body once as the engine, then throws. */
        THROW_AFTER_TAKING
    }

    private final HttpRequest request;
    private final Deque<Behaviour> script = new ArrayDeque<>();
    private final List<HttpResponse> handed = new ArrayList<>();
    private final List<CountingBody> bodies = new ArrayList<>();

    public ScriptedHttpExchange(HttpRequest request, CountingBody... knownBodies) {
        this.request = request;
        this.bodies.addAll(List.of(knownBodies));
    }

    public ScriptedHttpExchange script(Behaviour... behaviours) {
        script.addAll(List.of(behaviours));
        return this;
    }

    /** Registers a body so the engine double can release it. */
    public ScriptedHttpExchange know(CountingBody body) {
        bodies.add(body);
        return this;
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public void respond(HttpResponse response) {
        handed.add(response);
        Behaviour behaviour = script.isEmpty() ? Behaviour.ACCEPT : script.poll();
        if (behaviour == Behaviour.THROW_BEFORE_TAKING) {
            throw new IllegalStateException("exchange refused the response");
        }
        for (CountingBody body : bodies) {
            if (response.body() == body.buffer()) {
                body.releaseAsEngine();
            }
        }
        if (behaviour == Behaviour.THROW_AFTER_TAKING) {
            throw new IllegalStateException("exchange failed after taking the response");
        }
    }

    public List<HttpResponse> handed() {
        return List.copyOf(handed);
    }
}
