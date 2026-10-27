package org.sitenetsoft.quarkus.atproto.oauth.flow;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;
import org.sitenetsoft.atproto.syntax.SyntaxException;
import org.sitenetsoft.quarkus.atproto.identity.HandleNotFound;
import org.sitenetsoft.quarkus.atproto.identity.IdentityException;
import org.sitenetsoft.quarkus.atproto.identity.IdentityResolutionFailed;
import org.sitenetsoft.quarkus.atproto.identity.NoPds;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.discovery.OAuthDiscoveryException;
import org.sitenetsoft.quarkus.safefetch.SafeFetchException;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;

/** Writing the login routes' answers, and reading their parameters (extension internal). */
public final class Responses {

    private static final Logger LOG = Logger.getLogger("org.sitenetsoft.quarkus.atproto.oauth.flow");
    static final String NO_ACCOUNT = "No AT Protocol account found for this handle";
    private static final int MAX_FORM = 8 * 1024;

    private Responses() {
    }

    /** Query parameters, plus the urlencoded form body of a POST (first value of each). */
    public static Uni<Map<String, String>> params(RoutingContext ctx) {
        Map<String, String> out = new HashMap<>();
        ctx.queryParams().forEach(e -> out.putIfAbsent(e.getKey(), e.getValue()));
        if (ctx.request().method() != HttpMethod.POST) {
            return Uni.createFrom().item(out);
        }
        // the login route has a BodyHandler (OAuthRecorder), so the body is already read
        Buffer handled = ctx.body() == null ? null : ctx.body().buffer();
        return Uni.createFrom().item(handled).map(b -> {
            if (b != null && b.length() > MAX_FORM) {
                throw new LoginFailure(413, "invalid_request", "The login form is too large");
            }
            String contentType = String.valueOf(ctx.request().getHeader("Content-Type"));
            if (b != null && b.length() > 0 && contentType.startsWith("application/x-www-form-urlencoded")) {
                new QueryStringDecoder(b.toString(StandardCharsets.UTF_8), StandardCharsets.UTF_8, false).parameters()
                        .forEach((k, v) -> {
                            if (!v.isEmpty()) {
                                out.putIfAbsent(k, v.get(0));
                            }
                        });
            }
            return out;
        });
    }

    /** Runs {@code write} on the request's Vert.x context. */
    public static void on(Context context, Runnable write) {
        if (Context.isOnEventLoopThread() && context == io.vertx.core.Vertx.currentContext()) {
            write.run();
        } else {
            context.runOnContext(v -> write.run());
        }
    }

    static void redirect(RoutingContext ctx, String location) {
        if (ctx.response().ended()) {
            return;
        }
        ctx.response().setStatusCode(302).putHeader("Location", location).putHeader("Cache-Control", "no-store")
                .end();
    }

    /** A failed login step: the error page (when configured) or the status with the reason as text. */
    public static void fail(RoutingContext ctx, OAuthConfig config, String step, Throwable error) {
        LoginFailure f = classify(error);
        if (f.status() >= 500 && !(error instanceof LoginFailure)) {
            LOG.warnf("AT Protocol %s failed: %s (%s)", step, f.getMessage(), describe(error));
        } else {
            LOG.infof("AT Protocol %s refused: %s", step, f.getMessage());
        }
        if (ctx.response().ended()) {
            return;
        }
        Optional<String> errorPage = config.errorPage();
        if (errorPage.isPresent()) {
            String page = errorPage.get();
            redirect(ctx, page + (page.contains("?") ? "&" : "?") + "error="
                    + URLEncoder.encode(f.code(), StandardCharsets.UTF_8));
            return;
        }
        ctx.response().setStatusCode(f.status()).putHeader("Content-Type", "text/plain; charset=utf-8")
                .putHeader("Cache-Control", "no-store").end(f.getMessage());
    }

    /** Maps any failure of the flow to a {@link LoginFailure}. */
    static LoginFailure classify(Throwable error) {
        Throwable e = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return switch (e) {
            case LoginFailure f -> f;
            case HandleNotFound h -> new LoginFailure(400, "account_not_found", NO_ACCOUNT, h);
            case IdentityResolutionFailed r when noSuchDomain(r) -> new LoginFailure(400, "account_not_found",
                    NO_ACCOUNT, r);
            case IdentityResolutionFailed r -> new LoginFailure(503, "identity_unavailable",
                    "This account could not be looked up right now; please try again", r);
            case NoPds n -> new LoginFailure(502, "no_pds", "This account has no usable PDS", n);
            case IdentityException i -> new LoginFailure(502, "identity_error", "This account could not be resolved", i);
            case SyntaxException s -> new LoginFailure(400, "invalid_handle",
                    "Not a handle, a DID or a server URL", s);
            case OAuthDiscoveryException d -> new LoginFailure(502, "discovery_failed",
                    "The account's authorization server failed the check '" + d.check() + "'", d);
            case OAuthServerError o -> new LoginFailure(o.endpoint().equals("par") ? 502 : 401, o.error(),
                    "The authorization server refused the " + o.endpoint() + " request: " + o.error()
                            + (o.description() == null ? "" : " (" + o.description() + ")"), o);
            case SafeFetchException s -> new LoginFailure(502, "server_unreachable",
                    "The authorization server could not be reached", s);
            default -> new LoginFailure(500, "server_error", "The AT Protocol login failed", e);
        };
    }

    /**
     * A handle on a domain that does not exist surfaces as {@link IdentityResolutionFailed} (DNS cleanly empty, HTTPS
     * failing with an unknown host); that is "no such account", not "try again".
     */
    static boolean noSuchDomain(IdentityResolutionFailed e) {
        if (e.getSuppressed().length > 0 || e.getMessage() == null || !e.getMessage().contains("no did= TXT record")) {
            return false; // both lookups errored, or DNS itself failed
        }
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    /** An exception's class and message, without causes (never a token: our messages carry none). */
    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /**
     * {@code target} when it is a local path ({@code /...}, not {@code //...} or {@code /\...}, no scheme or
     * authority), else {@code fallback}.
     */
    static String localTarget(String target, String fallback) {
        if (target == null || target.isEmpty() || target.length() > 2048 || !target.startsWith("/")
                || target.startsWith("//") || target.startsWith("/\\") || target.chars().anyMatch(c -> c < 0x20)) {
            return fallback;
        }
        try {
            URI u = new URI(target);
            return u.getScheme() == null && u.getRawAuthority() == null ? target : fallback;
        } catch (java.net.URISyntaxException e) {
            return fallback;
        }
    }
}
