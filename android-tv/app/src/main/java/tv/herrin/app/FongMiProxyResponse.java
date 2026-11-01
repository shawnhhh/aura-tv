/*
 * Derived from FongMi/TV, GPL-3.0; upstream commit
 * 5e191aaced194e5cac5668cc91908ecb65336222, server/process/Proxy.java.
 * HERRIN modifications (2026-11-01): package/visibility and host Nano.error adapter.
 * Original project copyright and license: android-tv/LICENSE-GPL-3.0.md.
 */
package tv.herrin.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.IStatus;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public final class FongMiProxyResponse {
    private static final String INVALID_RESPONSE = "Invalid proxy response";
    public static Response createResponse(Object[] rs) {
        if (rs == null || rs.length == 0) return markError(NanoHTTPD.newFixedLengthResponse(toStatus(502), "text/plain", INVALID_RESPONSE));
        if (rs[0] instanceof Response response) return markError(response);
        if (rs.length < 3) return markError(NanoHTTPD.newFixedLengthResponse(toStatus(502), "text/plain", INVALID_RESPONSE));
        InputStream stream = (InputStream) rs[2];
        try {
            Response response = NanoHTTPD.newChunkedResponse(toStatus((Integer) rs[0]), (String) rs[1], stream);
            addHeaders(response, rs.length > 3 ? rs[3] : null);
            return markError(response);
        } catch (RuntimeException | Error error) {
            closeFailedStream(stream, error);
            throw error;
        }
    }

    private static void addHeaders(Response response, Object headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> entry : ((Map<String, String>) headers).entrySet()) {
            String name = entry.getKey();
            if (!name.equalsIgnoreCase("Content-Length") && !name.equalsIgnoreCase("Transfer-Encoding")) response.addHeader(name, entry.getValue());
        }
    }

    private static void closeFailedStream(InputStream stream, Throwable error) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException | RuntimeException | Error failure) {
            if (failure != error) error.addSuppressed(failure);
        }
    }

    private static Response markError(Response response) {
        if (response.getStatus().getRequestStatus() >= 400) response.addHeader("X-TV-Proxy-Error", "1");
        return response;
    }

    private static IStatus toStatus(int code) {
        Status status = Status.lookup(code);
        return status != null ? status : code >= 100 && code <= 599 ? new ProxyStatus(code) : Status.INTERNAL_ERROR;
    }

    private record ProxyStatus(int code) implements IStatus {

        @Override
        public String getDescription() {
            return code + " Proxy Status";
        }

        @Override
        public int getRequestStatus() {
            return code;
        }
    }
}
