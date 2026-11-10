package eu.exeris.caps.gatewaycore.testutil;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A response body double whose {@code close()} always throws the configured failure and counts the
 * call. Only {@code close()} is supported; any other call fails loudly.
 */
public final class ThrowingBody {

    private final AtomicInteger closes = new AtomicInteger();
    private final LoanedBuffer buffer;

    public ThrowingBody(Throwable closeFailure) {
        this.buffer = (LoanedBuffer) Proxy.newProxyInstance(
                LoanedBuffer.class.getClassLoader(),
                new Class<?>[] {LoanedBuffer.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "ThrowingBody";
                        };
                    }
                    if (method.getName().equals("close")) {
                        closes.incrementAndGet();
                        throw closeFailure;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    public LoanedBuffer buffer() {
        return buffer;
    }

    public int closes() {
        return closes.get();
    }
}
