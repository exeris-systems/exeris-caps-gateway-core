package eu.exeris.caps.gatewaycore.internal;

import eu.exeris.caps.gatewaycore.api.PolicyChain;
import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpTypedResponse;

import java.util.Map;
import java.util.Objects;

/**
 * Intercepting {@link HttpExchange} wrapper that invokes {@link PolicyChain#executePostRouting(HttpExchange, HttpResponse)}
 * when downstream handlers respond.
 */
final class PolicyInterceptingHttpExchange implements HttpExchange {

    private final HttpExchange delegate;
    private final PolicyChain policyChain;

    PolicyInterceptingHttpExchange(HttpExchange delegate, PolicyChain policyChain) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policyChain = Objects.requireNonNull(policyChain, "policyChain");
    }

    @Override
    public HttpRequest request() {
        return delegate.request();
    }

    @Override
    public Map<String, String> pathParams() {
        return delegate.pathParams();
    }

    @Override
    public void respond(HttpResponse response) {
        Objects.requireNonNull(response, "response");
        HttpResponse postProcessed = policyChain.executePostRouting(delegate, response);
        delegate.respond(postProcessed != null ? postProcessed : response);
    }

    @Override
    public void respond(HttpTypedResponse typedResponse) {
        Objects.requireNonNull(typedResponse, "typedResponse");
        // Policies act on HttpResponse, so they see the typed response's status and headers. The
        // payload is encoded by the delegate and is not visible to them.
        HttpResponse view = HttpResponse.noBody(typedResponse.status(), request().version(), typedResponse.headers());
        HttpResponse postProcessed = policyChain.executePostRouting(delegate, view);
        if (postProcessed == null || postProcessed == view) {
            delegate.respond(typedResponse);
        } else if (postProcessed.hasBody()) {
            // A policy that replaced the body supplies the whole response
            delegate.respond(postProcessed);
        } else {
            delegate.respond(new HttpTypedResponse(
                    postProcessed.status(), postProcessed.headers(), typedResponse.payload()));
        }
    }
}
