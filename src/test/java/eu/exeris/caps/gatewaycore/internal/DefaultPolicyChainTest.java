package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.GatewayPolicy;
import eu.exeris.caps.gatewaycore.api.PolicyResult;
import eu.exeris.caps.gatewaycore.testutil.RecordingHttpExchange;
import eu.exeris.kernel.spi.http.HttpHeader;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultPolicyChainTest")
class DefaultPolicyChainTest {

    @Test
    @DisplayName("executes pre-routing in ascending order of policy order")
    void executesPreRoutingInOrder() {
        DefaultPolicyChain chain = new DefaultPolicyChain();
        List<String> executed = new ArrayList<>();

        chain.addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "second";
            }

            @Override
            public int order() {
                return 20;
            }

            @Override
            public PolicyResult preRouting(eu.exeris.kernel.spi.http.HttpExchange exchange) {
                executed.add("second");
                return PolicyResult.proceed();
            }
        });

        chain.addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "first";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public PolicyResult preRouting(eu.exeris.kernel.spi.http.HttpExchange exchange) {
                executed.add("first");
                return PolicyResult.proceed();
            }
        });

        RecordingHttpExchange exchange = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/test", HttpVersion.HTTP_1_1, List.of())
        );

        PolicyResult result = chain.executePreRouting(exchange);
        assertThat(result.isShortCircuit()).isFalse();
        assertThat(executed).containsExactly("first", "second");
    }

    @Test
    @DisplayName("short-circuits pre-routing and skips subsequent policies")
    void shortCircuitsPreRouting() {
        DefaultPolicyChain chain = new DefaultPolicyChain();
        List<String> executed = new ArrayList<>();

        chain.addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "auth-blocker";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public PolicyResult preRouting(eu.exeris.kernel.spi.http.HttpExchange exchange) {
                executed.add("auth-blocker");
                return PolicyResult.shortCircuit(HttpStatus.UNAUTHORIZED);
            }
        });

        chain.addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "never-reached";
            }

            @Override
            public int order() {
                return 20;
            }

            @Override
            public PolicyResult preRouting(eu.exeris.kernel.spi.http.HttpExchange exchange) {
                executed.add("never-reached");
                return PolicyResult.proceed();
            }
        });

        RecordingHttpExchange exchange = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/secure", HttpVersion.HTTP_1_1, List.of())
        );

        PolicyResult result = chain.executePreRouting(exchange);
        assertThat(result.isShortCircuit()).isTrue();
        assertThat(result.response().status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(executed).containsExactly("auth-blocker");
    }

    @Test
    @DisplayName("executes post-routing in reverse order (onion pattern) and transforms response")
    void executesPostRoutingInReverseOrder() {
        DefaultPolicyChain chain = new DefaultPolicyChain();

        chain.addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "outer";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public HttpResponse postRouting(eu.exeris.kernel.spi.http.HttpExchange exchange, HttpResponse response) {
                List<HttpHeader> headers = new ArrayList<>(response.headers());
                headers.add(new HttpHeader("X-Outer", "applied"));
                return new HttpResponse(response.status(), response.version(), headers, response.body());
            }
        });

        chain.addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "inner";
            }

            @Override
            public int order() {
                return 20;
            }

            @Override
            public HttpResponse postRouting(eu.exeris.kernel.spi.http.HttpExchange exchange, HttpResponse response) {
                List<HttpHeader> headers = new ArrayList<>(response.headers());
                headers.add(new HttpHeader("X-Inner", "applied"));
                return new HttpResponse(response.status(), response.version(), headers, response.body());
            }
        });

        RecordingHttpExchange exchange = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/test", HttpVersion.HTTP_1_1, List.of())
        );
        HttpResponse initial = new HttpResponse(HttpStatus.OK, HttpVersion.HTTP_1_1, List.of(), null);

        HttpResponse finalResponse = chain.executePostRouting(exchange, initial);
        assertThat(finalResponse.headers()).extracting(HttpHeader::name).containsExactly("X-Inner", "X-Outer");
    }

    @Test
    @DisplayName("removes policy by name")
    void removesPolicyByName() {
        DefaultPolicyChain chain = new DefaultPolicyChain();
        chain.addPolicy(new GatewayPolicy() {
            @Override
            public String name() {
                return "temp-policy";
            }
        });

        assertThat(chain.policies()).hasSize(1);
        boolean removed = chain.removePolicy("temp-policy");
        assertThat(removed).isTrue();
        assertThat(chain.policies()).isEmpty();
    }
}
