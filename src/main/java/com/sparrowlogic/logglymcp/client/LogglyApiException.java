package com.sparrowlogic.logglymcp.client;

/**
 * Thrown when the Loggly API returns a non-2xx response. Carries the HTTP status and response
 * body so the failure surfaces as a useful MCP tool error.
 */
public class LogglyApiException extends RuntimeException {

    private final int status;

    public LogglyApiException(int status, String body) {
        super("Loggly API request failed with HTTP " + status
                + (body == null || body.isBlank() ? "" : ": " + body));
        this.status = status;
    }

    public int getStatus() {
        return this.status;
    }
}
