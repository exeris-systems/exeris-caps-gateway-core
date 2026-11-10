package eu.exeris.caps.gatewaycore.testutil;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A response body double that tells the two releasers apart. {@link #buffer()} is the
 * {@link LoanedBuffer} a test puts into a response; every {@code close()} call on it is a release by
 * the code under test and is counted by {@link #libraryCloses()}. A release by the engine goes through
 * {@link #releaseAsEngine()} and is counted by {@link #engineCloses()}. Only {@code close()},
 * {@code isAlive()} and {@code refCount()} are supported, so a test that touches the buffer in any
 * other way fails loudly.
 */
public final class CountingBody {

    private final AtomicInteger libraryCloses = new AtomicInteger();
    private final AtomicInteger engineCloses = new AtomicInteger();
    private final LoanedBuffer buffer;

    public CountingBody() {
        this.buffer = (LoanedBuffer) Proxy.newProxyInstance(
                LoanedBuffer.class.getClassLoader(),
                new Class<?>[] {LoanedBuffer.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "CountingBody";
                        };
                    }
                    return switch (method.getName()) {
                        case "close" -> {
                            libraryCloses.incrementAndGet();
                            yield null;
                        }
                        case "isAlive" -> engineCloses.get() == 0;
                        case "refCount" -> engineCloses.get() == 0 ? 1 : 0;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    public LoanedBuffer buffer() {
        return buffer;
    }

    /** Releases made by the code under test through {@code close()}. */
    public int libraryCloses() {
        return libraryCloses.get();
    }

    /** Releases made by the engine double. */
    public int engineCloses() {
        return engineCloses.get();
    }

    /** The engine releases the body; after this {@code isAlive()} is false. */
    public void releaseAsEngine() {
        engineCloses.incrementAndGet();
    }
}
