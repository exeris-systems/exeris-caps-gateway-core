package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.testutil.CountingBody;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Releasing a replaced response body allocates nothing")
class ReplacedBodyAllocationTest {

    private static final int WARM_UP = 200_000;
    private static final int ITERATIONS = 200_000;

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Written by the control policy so the allocation cannot be elided. */
    private static volatile Object sink;

    private static DefaultPolicyChain chainWith(BiFunction<HttpExchange, HttpResponse, HttpResponse> post) {
        DefaultPolicyChain chain = new DefaultPolicyChain();
        chain.addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "p";
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public HttpResponse postRouting(HttpExchange exchange, HttpResponse response) {
                return post.apply(exchange, response);
            }
        });
        return chain;
    }

    /** Bytes allocated by the current thread across {@code iterations} calls of the chain. */
    private static long allocated(DefaultPolicyChain chain, HttpExchange exchange, HttpResponse input, int iterations) {
        long id = Thread.currentThread().threadId();
        long before = THREADS.getThreadAllocatedBytes(id);
        for (int i = 0; i < iterations; i++) {
            chain.executePostRouting(exchange, input);
        }
        return THREADS.getThreadAllocatedBytes(id) - before;
    }

    private static long measure(DefaultPolicyChain chain, HttpExchange exchange, HttpResponse input) {
        allocated(chain, exchange, input, WARM_UP);
        return allocated(chain, exchange, input, ITERATIONS);
    }

    private final HttpExchange exchange = new eu.exeris.caps.gatewaycore.testutil.ScriptedHttpExchange(
            HttpRequest.noBody(HttpMethod.GET, "/x", HttpVersion.HTTP_1_1, List.of()));
    private final HttpResponse original = new HttpResponse(
            HttpStatus.OK, HttpVersion.HTTP_1_1, List.of(), new CountingBody().buffer());
    private final HttpResponse replaced = new HttpResponse(
            HttpStatus.FORBIDDEN, HttpVersion.HTTP_1_1, List.of(), new CountingBody().buffer());

    @Test
    @DisplayName("Q1: the measurement detects one small object per iteration")
    void controlDetectsOneObjectPerIteration() {
        assumeMeasurable();
        long keep = measure(chainWith((e, r) -> r), exchange, original);
        long allocating = measure(chainWith((e, r) -> {
            sink = new Object();
            return r;
        }), exchange, original);

        // an Object is at least 16 bytes
        assertThat(allocating - keep).isGreaterThanOrEqualTo(16L * ITERATIONS * 9 / 10);
    }

    @Test
    @DisplayName("Q1: replacing the body allocates no more per call than keeping it")
    void replacementAllocatesNothingBeyondKeeping() {
        assumeMeasurable();
        long keep = measure(chainWith((e, r) -> r), exchange, original);
        long replace = measure(chainWith((e, r) -> replaced), exchange, original);

        System.out.printf("Q1 allocated bytes over %d calls: keep=%d replace=%d delta=%d%n",
                ITERATIONS, keep, replace, replace - keep);
        // far below one byte per call: a single throwaway object per release would add >= 16 * ITERATIONS
        assertThat(replace - keep).isLessThan(ITERATIONS / 10);
    }

    private static void assumeMeasurable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(THREADS.isThreadAllocatedMemorySupported()
                && THREADS.isThreadAllocatedMemoryEnabled());
    }
}
