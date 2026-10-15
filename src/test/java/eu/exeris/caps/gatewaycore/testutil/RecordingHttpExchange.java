package eu.exeris.caps.gatewaycore.testutil;

import eu.exeris.kernel.spi.http.HttpExchange;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpResponse;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.http.HttpTypedResponse;

import java.util.Map;

public final class RecordingHttpExchange implements HttpExchange {

    private final HttpRequest request;
    private HttpResponse response;
    private HttpStatus status;
    private Object body;
    private Map<String, String> pathParams = Map.of();

    public RecordingHttpExchange(HttpRequest request) {
        this.request = request;
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public Map<String, String> pathParams() {
        return pathParams;
    }

    public void setPathParams(Map<String, String> pathParams) {
        this.pathParams = pathParams;
    }

    @Override
    public void respond(HttpResponse response) {
        this.response = response;
        this.status = response != null ? response.status() : null;
    }

    @Override
    public void respond(HttpTypedResponse typedResponse) {
        this.status = typedResponse != null ? typedResponse.status() : null;
    }

    @Override
    public void respond(HttpStatus status, Object body) {
        this.status = status;
        this.body = body;
    }

    @Override
    public void respond(HttpStatus status) {
        this.status = status;
    }

    public HttpResponse response() {
        return response;
    }

    public HttpStatus status() {
        return status;
    }

    public Object body() {
        return body;
    }
}
