package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.jose.ClientAssertions;
import org.sitenetsoft.quarkus.atproto.oauth.jose.DpopProofs;
import org.sitenetsoft.quarkus.safefetch.FetchOptions;
import org.sitenetsoft.quarkus.safefetch.FetchResponse;
import org.sitenetsoft.quarkus.safefetch.ReactiveSafeFetchClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;

/**
 * Form POSTs to an authorization server's endpoints (PAR, token, revocation) with a DPoP proof carrying that
 * server's nonce and, for a confidential client, a fresh {@code private_key_jwt} assertion. On
 * {@code use_dpop_nonce} the request is rebuilt (new proof, new assertion: both are single use) with the nonce from
 * the answer and sent once more. Every answer's {@code DPoP-Nonce} is remembered. Nothing here logs a form value.
 */
@ApplicationScoped
public class AuthServerClient {

    private static final Logger LOG = Logger.getLogger(AuthServerClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_RESPONSE = 64 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ReactiveSafeFetchClient http;
    private final NonceStore nonces;
    private final ClientIdentity client;

    public AuthServerClient(ReactiveSafeFetchClient http, NonceStore nonces, ClientIdentity client) {
        this.http = http;
        this.nonces = nonces;
        this.client = client;
    }

    /**
     * POSTs {@code params} plus the client authentication to {@code endpoint}.
     *
     * @param name the call, for errors ({@code par}, {@code token}, ...)
     * @param issuer the authorization server's issuer (the assertion's audience)
     * @return the HTTP status and the JSON body (an empty object when there is none)
     */
    public Uni<Answer> post(String name, URI endpoint, String issuer, KeyPair dpopKey, Map<String, String> params) {
        return attempt(name, endpoint, issuer, dpopKey, params, true);
    }

    /** An answer: status and JSON body. */
    public record Answer(int status, JsonNode body) {
        public boolean success() {
            return status >= 200 && status < 300;
        }

        /** The OAuth error of a failed answer as an exception. */
        public OAuthServerError error(String name) {
            String error = body.path("error").isTextual() ? errorCode(body.get("error").asText()) : "http_" + status;
            String description = body.path("error_description").isTextual()
                    ? abbreviate(body.get("error_description").asText()) : null;
            return new OAuthServerError(name, status, error, description);
        }
    }

    private Uni<Answer> attempt(String name, URI endpoint, String issuer, KeyPair dpopKey, Map<String, String> params,
            boolean first) {
        return Uni.createFrom().deferred(() -> {
            Instant now = Instant.now();
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", client.clientId());
            form.putAll(params);
            if (client.confidential()) {
                form.put("client_assertion_type", ClientAssertions.TYPE);
                form.put("client_assertion",
                        ClientAssertions.assertion(client.signingKey().orElseThrow(), client.clientId(), issuer, now));
            }
            String proof = DpopProofs.proof(dpopKey, "POST", endpoint, nonces.get(endpoint), Optional.empty(), now);
            FetchOptions options = FetchOptions.defaults().withTimeout(TIMEOUT).withMaxResponseSize(MAX_RESPONSE)
                    .withHeader("Content-Type", "application/x-www-form-urlencoded")
                    .withHeader("Accept", "application/json").withHeader("DPoP", proof);
            return http.post(endpoint, encode(form), options);
        }).chain(r -> {
            Optional<String> nonce = r.header("DPoP-Nonce");
            nonce.ifPresent(n -> nonces.put(endpoint, n));
            Answer answer = new Answer(r.status(), json(r));
            if (first && (r.status() == 400 || r.status() == 401) && nonce.isPresent()
                    && "use_dpop_nonce".equals(answer.body().path("error").asText(null))) {
                LOG.debugf("%s at %s asked for a DPoP nonce; retrying once", name, issuer);
                return attempt(name, endpoint, issuer, dpopKey, params, false);
            }
            return Uni.createFrom().item(answer);
        });
    }

    private static JsonNode json(FetchResponse r) {
        try {
            JsonNode n = r.body() == null || r.body().length == 0 ? null : JSON.readTree(r.body());
            return n != null && n.isObject() ? n : JSON.createObjectNode();
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static byte[] encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final java.util.regex.Pattern ERROR_CODE = java.util.regex.Pattern.compile("[a-z0-9_]{1,64}");

    /**
     * An authorization server's OAuth {@code error} code as we log, show and forward it: kept when it matches
     * {@code [a-z0-9_]{1,64}}, else {@code invalid_response} (anyone can run an authorization server).
     */
    public static String errorCode(String error) {
        return error != null && ERROR_CODE.matcher(error).matches() ? error : "invalid_response";
    }

    /** Server text is shown to users and logged: printable characters, at most 200. */
    static String abbreviate(String s) {
        String clean = s.replaceAll("[\\p{Cntrl}]", " ");
        return clean.length() <= 200 ? clean : clean.substring(0, 200) + "…";
    }
}
