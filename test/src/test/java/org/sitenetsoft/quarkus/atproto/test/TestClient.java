package org.sitenetsoft.quarkus.atproto.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.sitenetsoft.multikey.Jwk;
import org.sitenetsoft.multikey.JwkThumbprint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A deliberately independent, minimal OAuth + DPoP client (JDK crypto, multikey only for JWK encoding), so the fakes are
 * not tested against their own JWT code.
 */
final class TestClient {

  static final ObjectMapper JSON = new ObjectMapper();
  static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final SecureRandom RANDOM = new SecureRandom();

  final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
      .connectTimeout(Duration.ofSeconds(5)).build();
  final KeyPair dpopKey = ecKey();
  String asNonce;
  String pdsNonce;

  static KeyPair ecKey() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return g.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static String random() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return B64.encodeToString(b);
  }

  static String sha256(String s) {
    try {
      return B64.encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static String jwt(Map<String, Object> header, Map<String, Object> claims, KeyPair key) {
    try {
      String input = B64.encodeToString(JSON.writeValueAsBytes(header)) + "."
          + B64.encodeToString(JSON.writeValueAsBytes(claims));
      Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
      s.initSign(key.getPrivate());
      s.update(input.getBytes(StandardCharsets.US_ASCII));
      return input + "." + B64.encodeToString(s.sign());
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static JsonNode claims(String jwt) {
    try {
      return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  String jkt() {
    return JwkThumbprint.sha256(Jwk.of(dpopKey.getPublic()));
  }

  /** A DPoP proof; {@code ath} only when {@code accessToken} is not null. */
  String proof(String method, URI url, String nonce, String accessToken) {
    return proof(method, url, nonce, accessToken == null ? null : sha256(accessToken), UUID.randomUUID().toString());
  }

  String proof(String method, URI url, String nonce, String ath, String jti) {
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("typ", "dpop+jwt");
    header.put("alg", "ES256");
    header.put("jwk", Jwk.of(dpopKey.getPublic()));
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("jti", jti);
    claims.put("htm", method);
    claims.put("htu", url.toString());
    claims.put("iat", Instant.now().getEpochSecond());
    if (nonce != null) {
      claims.put("nonce", nonce);
    }
    if (ath != null) {
      claims.put("ath", ath);
    }
    return jwt(header, claims, dpopKey);
  }

  static String form(Map<String, String> params) {
    StringBuilder sb = new StringBuilder();
    params.forEach((k, v) -> {
      if (v != null) {
        sb.append(sb.isEmpty() ? "" : "&").append(enc(k)).append('=').append(enc(v));
      }
    });
    return sb.toString();
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  static Map<String, String> query(URI uri) {
    Map<String, String> out = new LinkedHashMap<>();
    if (uri.getRawQuery() != null) {
      for (String p : uri.getRawQuery().split("&")) {
        int i = p.indexOf('=');
        out.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8),
            URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  HttpResponse<String> send(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  HttpResponse<String> get(URI url) {
    return send(HttpRequest.newBuilder(url).GET().build());
  }

  /** POST a form to the AS with a DPoP proof using the stored AS nonce (no retry). */
  HttpResponse<String> postAs(URI url, Map<String, String> params) {
    HttpResponse<String> r = send(HttpRequest.newBuilder(url)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("DPoP", proof("POST", url, asNonce, null))
        .POST(HttpRequest.BodyPublishers.ofString(form(params))).build());
    r.headers().firstValue("DPoP-Nonce").ifPresent(n -> asNonce = n);
    return r;
  }

  /** Like {@link #postAs} with one retry on {@code use_dpop_nonce}. */
  HttpResponse<String> postAsWithNonce(URI url, Map<String, String> params) {
    HttpResponse<String> r = postAs(url, params);
    if (r.statusCode() == 400 && r.body().contains("use_dpop_nonce")) {
      r = postAs(url, params);
    }
    return r;
  }

  static JsonNode json(HttpResponse<String> r) {
    try {
      return JSON.readTree(r.body());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ---- PDS --------------------------------------------------------------------------------------------------------

  HttpResponse<String> xrpcPost(URI url, String accessToken, String body, String contentType, String proof) {
    HttpRequest.Builder b = HttpRequest.newBuilder(url).header("Content-Type", contentType)
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (accessToken != null) {
      b.header("Authorization", "DPoP " + accessToken).header("DPoP", proof);
    }
    HttpResponse<String> r = send(b.build());
    r.headers().firstValue("DPoP-Nonce").ifPresent(n -> pdsNonce = n);
    return r;
  }

  /** POST JSON to the PDS with a fresh proof, retrying once on {@code use_dpop_nonce}. */
  HttpResponse<String> xrpc(Session s, URI url, String body) {
    return xrpc(s, url, body, "application/json");
  }

  HttpResponse<String> xrpc(Session s, URI url, String body, String contentType) {
    HttpResponse<String> r = xrpcPost(url, s.accessToken, body, contentType, proof("POST", url, pdsNonce, s.accessToken));
    if (r.statusCode() == 401 && r.body().contains("use_dpop_nonce")) {
      r = xrpcPost(url, s.accessToken, body, contentType, proof("POST", url, pdsNonce, s.accessToken));
    }
    return r;
  }

  // ---- a whole login ----------------------------------------------------------------------------------------------

  record Session(String accessToken, String refreshToken, JsonNode tokenResponse) {
  }

  static String devClientId(String redirectUri, String scope) {
    return "http://localhost?redirect_uri=" + enc(redirectUri) + "&scope=" + enc(scope);
  }

  static final String REDIRECT = "http://127.0.0.1:1/callback";

  /** PAR (with nonce handshake), authorize, token exchange for the localhost dev client. */
  Session login(FakeAtproto fake, FakeAccount account, String scope) {
    String clientId = devClientId(REDIRECT, scope);
    String verifier = random();
    String state = random();
    Map<String, String> par = new LinkedHashMap<>();
    par.put("client_id", clientId);
    par.put("response_type", "code");
    par.put("code_challenge", sha256(verifier));
    par.put("code_challenge_method", "S256");
    par.put("state", state);
    par.put("redirect_uri", REDIRECT);
    par.put("scope", scope);
    par.put("login_hint", account.handle().value());
    HttpResponse<String> r = postAsWithNonce(fake.asUrl().resolve("/oauth/par"), par);
    assertEquals(201, r.statusCode(), r.body());
    String requestUri = json(r).get("request_uri").asText();
    HttpResponse<String> auth = get(fake.asUrl().resolve("/oauth/authorize?client_id=" + enc(clientId)
        + "&request_uri=" + enc(requestUri)));
    assertEquals(302, auth.statusCode(), auth.body());
    Map<String, String> cb = query(URI.create(auth.headers().firstValue("Location").orElseThrow()));
    assertEquals(state, cb.get("state"));
    Map<String, String> tok = new LinkedHashMap<>();
    tok.put("grant_type", "authorization_code");
    tok.put("code", cb.get("code"));
    tok.put("code_verifier", verifier);
    tok.put("redirect_uri", REDIRECT);
    tok.put("client_id", clientId);
    HttpResponse<String> t = postAsWithNonce(fake.asUrl().resolve("/oauth/token"), tok);
    assertEquals(200, t.statusCode(), t.body());
    JsonNode body = json(t);
    return new Session(body.get("access_token").asText(), body.get("refresh_token").asText(), body);
  }
}
