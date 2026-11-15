package com.safesphere.backend.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * L1 (laptop behind Cloudflare Tunnel + nginx): helpers for the two real-address tests. They talk to a REAL embedded Tomcat
 * over the loopback interface, because MockMvc never runs Tomcat's RemoteIpValve, so only a real HTTP request can show what
 * `server.forward-headers-strategy` does to the address the rate limits see.
 */
final class RealClientAddressSupport {

    private RealClientAddressSupport() {
    }

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /**
     * One failed sign-in (Phase N2: a code check with a challenge that does not exist, so no number's own limit can
     * be the reason for a refusal), as if sent by a proxy on this machine that says the real client address is
     * [forwardedFor]. Returns the HTTP status: 400 = counted as a failed attempt, 429 = the address is over its limit.
     */
    static int failedSignInFrom(int port, String forwardedFor) throws Exception {
        String challenge = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 43);
        String body = "{\"challenge\":\"" + challenge + "\",\"code\":\"000000\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/phone/verify"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
