package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.utils.Auth;

import java.io.IOException;
import java.net.ProtocolException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class ProxyRedirectInterceptor implements Interceptor {

    private static final int MAX_FOLLOW_UPS = 20;

    ProxyRedirectInterceptor(OkProxySelector selector) {
        // OkHttp 4.12 configures proxy selection at the OkHttpClient level;
        // unlike upstream OkHttp 5 it cannot override it on an interceptor Chain.
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        Response previousResponse = null;
        int followUpCount = 0;
        while (true) {
            Response response = chain.proceed(request);
            followUpCount += countFollowUps(response);
            response = withPriorResponse(response, previousResponse);
            checkFollowUps(response, followUpCount);
            NetOptions options = request.tag(NetOptions.class);
            if (options != null && !options.isRedirect()) return response;
            Request followUp = redirect(response, true);
            if (followUp == null) return response;
            RequestBody body = followUp.body();
            if (body != null && body.isOneShot()) return response;
            response.close();
            if (++followUpCount > MAX_FOLLOW_UPS) throw new ProtocolException("Too many follow-up requests: " + followUpCount);
            previousResponse = response.newBuilder().body(ResponseBody.create(null, new byte[0])).build();
            request = followUp;
        }
    }

    private int countFollowUps(Response response) {
        int count = 0;
        for (Response prior = response.priorResponse(); prior != null; prior = prior.priorResponse()) count++;
        return count;
    }

    private void checkFollowUps(Response response, int count) throws ProtocolException {
        if (count <= MAX_FOLLOW_UPS) return;
        response.close();
        throw new ProtocolException("Too many follow-up requests: " + count);
    }

    private Response withPriorResponse(Response response, Response previous) {
        if (previous == null) return response;
        Response prior = response.priorResponse();
        return response.newBuilder().priorResponse(prior == null ? previous : withPriorResponse(prior, previous)).build();
    }

    private Request redirect(Response response, boolean followSslRedirects) {
        HttpUrl target = redirectUrl(response, followSslRedirects);
        if (target == null) return null;
        Request.Builder builder = response.request().newBuilder();
        redirectMethod(builder, response.request(), response.code());
        if (!sameOrigin(response.request().url(), target)) builder.removeHeader("Authorization");
        Request redirected = builder.url(target).build();
        Auth.Context auth = redirected.tag(Auth.Context.class);
        return auth == null ? redirected : OkHttp.applyAuth(redirected, auth);
    }

    private HttpUrl redirectUrl(Response response, boolean followSslRedirects) {
        int code = response.code();
        if (code != 300 && code != 301 && code != 302 && code != 303 && code != 307 && code != 308) return null;
        String location = response.header("Location");
        if (location == null) return null;
        HttpUrl source = response.request().url();
        HttpUrl target = source.resolve(location);
        if (target == null || !source.scheme().equals(target.scheme()) && !followSslRedirects) return null;
        return target;
    }

    private void redirectMethod(Request.Builder builder, Request request, int code) {
        String method = request.method();
        if ("GET".equals(method) || "HEAD".equals(method)) return;
        if (redirectsToGet(method, code)) builder.method("GET", null).removeHeader("Transfer-Encoding").removeHeader("Content-Length").removeHeader("Content-Type");
        else builder.method(method, request.body());
    }

    private boolean sameOrigin(HttpUrl source, HttpUrl target) {
        return source.scheme().equals(target.scheme()) && source.host().equals(target.host()) && source.port() == target.port();
    }

    private boolean redirectsToGet(String method, int code) {
        if (code == 303) return !"PROPFIND".equals(method);
        if (code == 307 || code == 308) return false;
        return !"PROPFIND".equals(method) && !"QUERY".equals(method);
    }
}
