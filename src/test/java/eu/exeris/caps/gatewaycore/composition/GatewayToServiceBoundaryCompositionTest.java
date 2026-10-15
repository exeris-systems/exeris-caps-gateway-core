package eu.exeris.caps.gatewaycore.composition;

import eu.exeris.caps.gatewaycore.GatewayCoreModule;
import eu.exeris.caps.gatewaycore.api.GatewayLifecycle;
import eu.exeris.caps.gatewaycore.api.IngressBootstrap;
import eu.exeris.caps.gatewaycore.testutil.RecordingHttpExchange;
import eu.exeris.caps.serviceboundarycore.ServiceBoundaryCoreModule;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry;
import eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRoute;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.sdk.composition.CapManifest;
import eu.exeris.sdk.composition.CompositionBinding;
import eu.exeris.sdk.composition.runtime.CompositionConductor;
import eu.exeris.sdk.composition.runtime.CompositionStampAssertion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@DisplayName("GatewayToServiceBoundaryCompositionTest")
class GatewayToServiceBoundaryCompositionTest {

    @Test
    @DisplayName("SDK CompositionConductor boots and shuts down composed GatewayCoreModule and ServiceBoundaryCoreModule")
    void multiModuleCompositionLifecycle() {
        CapManifest.Module gatewayModule = new CapManifest.Module(
                "eu.exeris.caps.gatewaycore.GatewayCoreModule",
                new CapManifest.ModuleBody(
                        List.of(
                                new CapManifest.Provided("eu.exeris.caps.gatewaycore.api.GatewayLifecycle", "1.0.0"),
                                new CapManifest.Provided("eu.exeris.caps.gatewaycore.api.IngressBootstrap", "1.0.0")
                        ),
                        "eu.exeris.caps.gatewaycore.GatewayCoreModule"
                )
        );

        CapManifest.Module sbModule = new CapManifest.Module(
                "eu.exeris.caps.serviceboundarycore.ServiceBoundaryCoreModule",
                new CapManifest.ModuleBody(
                        List.of(
                                new CapManifest.Provided("eu.exeris.caps.serviceboundarycore.api.ApiSurfaceRegistry", "1.0.0"),
                                new CapManifest.Provided("eu.exeris.caps.serviceboundarycore.api.ServiceLifecycleHooks", "1.0.0"),
                                new CapManifest.Provided("eu.exeris.caps.serviceboundarycore.api.RequestContext", "1.0.0")
                        ),
                        "eu.exeris.caps.serviceboundarycore.ServiceBoundaryCoreModule"
                )
        );

        List<CapManifest.Module> modules = List.of(gatewayModule, sbModule);
        String contentBinding = CompositionBinding.compute(modules);

        CapManifest.Stamp stamp = new CapManifest.Stamp(
                true,
                "0.1.0",
                contentBinding
        );

        CapManifest manifest = new CapManifest(
                2,
                stamp,
                modules,
                List.of(
                        "eu.exeris.caps.gatewaycore.GatewayCoreModule",
                        "eu.exeris.caps.serviceboundarycore.ServiceBoundaryCoreModule"
                )
        );

        // Verify stamp consistency via SDK runtime assertion
        assertThatNoException().isThrownBy(() -> CompositionStampAssertion.assertConsistent(manifest));

        // Boot composition conductor
        try (CompositionConductor conductor = CompositionConductor.from(manifest).start()) {
            assertThat(conductor).isNotNull();
            // Conductor discovered both modules, instantiated them, and ran initialize() + ready()
        }
        // Conductor closed and executed drain() + terminate() in reverse order
    }

    @Test
    @DisplayName("Gateway Ingress routes requests into Service Boundary Core API surface with lifecycle coordination")
    void gatewayToServiceBoundaryDataPlane() throws Exception {
        GatewayCoreModule gatewayModule = new GatewayCoreModule();
        ServiceBoundaryCoreModule sbModule = new ServiceBoundaryCoreModule();

        ApiSurfaceRegistry sbRegistry = sbModule.apiSurfaceRegistry();
        IngressBootstrap ingress = gatewayModule.ingressBootstrap();
        GatewayLifecycle gatewayLifecycle = gatewayModule.gatewayLifecycle();

        AtomicBoolean healthActionCalled = new AtomicBoolean();
        ApiSurfaceRoute healthRoute = ApiSurfaceRoute.builder()
                .method(HttpMethod.GET)
                .pathPattern("/api/v1/health")
                .actionName("GatewayHealth#check")
                .handler(ex -> {
                    healthActionCalled.set(true);
                    ex.respond(HttpStatus.OK, "{\"status\":\"healthy\"}");
                })
                .build();

        AtomicBoolean orderActionCalled = new AtomicBoolean();
        ApiSurfaceRoute orderRoute = ApiSurfaceRoute.builder()
                .method(HttpMethod.POST)
                .pathPattern("/api/v1/orders/{orderId}")
                .actionName("Orders#create")
                .handler(ex -> {
                    orderActionCalled.set(true);
                    String orderId = ex.pathParams().get("orderId");
                    ex.respond(HttpStatus.CREATED, "{\"orderId\":\"" + orderId + "\"}");
                })
                .build();

        sbRegistry.registerRoute(healthRoute);
        sbRegistry.registerRoute(orderRoute);

        // Wire Service Boundary API surface as the Gateway Ingress handler for /api
        ingress.registerHandler("/api", sbRegistry);

        // 1. Before READY, Gateway Ingress rejects traffic to protect Service Boundary
        RecordingHttpExchange exBeforeReady = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/v1/health", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exBeforeReady);
        assertThat(exBeforeReady.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(healthActionCalled).isFalse();
        assertThat(gatewayLifecycle.servedRequestCount()).isZero();

        // 2. Lifecycle initialize and ready
        gatewayModule.initialize();
        sbModule.initialize();
        gatewayModule.ready();
        sbModule.ready();

        assertThat(gatewayLifecycle.isAcceptingTraffic()).isTrue();

        // 3. Dispatch GET request through Ingress into Service Boundary
        RecordingHttpExchange exGet = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/v1/health", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exGet);

        assertThat(healthActionCalled).isTrue();
        assertThat(exGet.status()).isEqualTo(HttpStatus.OK);
        assertThat(gatewayLifecycle.servedRequestCount()).isEqualTo(1);

        // 4. Dispatch POST parameterized request through Ingress into Service Boundary
        RecordingHttpExchange exPost = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.POST, "/api/v1/orders/ORD-9876", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exPost);

        assertThat(orderActionCalled).isTrue();
        assertThat(exPost.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(gatewayLifecycle.servedRequestCount()).isEqualTo(2);

        // 5. Dispatch unknown route through Ingress -> Service Boundary returns 404 NOT_FOUND
        RecordingHttpExchange exNotFound = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/v1/unknown", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exNotFound);
        assertThat(exNotFound.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gatewayLifecycle.servedRequestCount()).isEqualTo(3);

        // 6. Graceful draining: Gateway shuts ingress gate
        gatewayModule.drain(Duration.ofSeconds(15));
        sbModule.drain(Duration.ofSeconds(15));

        assertThat(gatewayLifecycle.isAcceptingTraffic()).isFalse();

        RecordingHttpExchange exDraining = new RecordingHttpExchange(
                HttpRequest.noBody(HttpMethod.GET, "/api/v1/health", HttpVersion.HTTP_1_1, List.of())
        );
        ingress.handle(exDraining);
        assertThat(exDraining.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        // 7. Termination
        sbModule.terminate();
        gatewayModule.terminate();
    }
}
