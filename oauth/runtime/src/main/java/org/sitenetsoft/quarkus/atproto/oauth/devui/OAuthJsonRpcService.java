package org.sitenetsoft.quarkus.atproto.oauth.devui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.ConfigProvider;
import org.sitenetsoft.quarkus.atproto.oauth.OAuthConfig;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientIdentity;
import org.sitenetsoft.quarkus.atproto.oauth.client.ClientMetadata;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSession;
import org.sitenetsoft.quarkus.atproto.oauth.session.AtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.InMemoryAtprotoSessionStore;
import org.sitenetsoft.quarkus.atproto.oauth.session.SessionStores;

/**
 * Dev UI backend for the AT Protocol OAuth card. Payloads hold public data only: the client metadata document and,
 * per session, DID, handle, PDS, scope and expiry; never tokens, DPoP keys or session ids. Results are plain maps and
 * lists (the Dev UI serialiser would write a Jackson {@code JsonNode} as a bean).
 */
public class OAuthJsonRpcService {

    /** Sessions listed at most. */
    static final int LIMIT = 200;

    private final ClientIdentity client;
    private final OAuthConfig config;
    private final SessionStores stores;

    @jakarta.inject.Inject
    public OAuthJsonRpcService(ClientIdentity client, OAuthConfig config, SessionStores stores) {
        this.client = client;
        this.config = config;
        this.stores = stores;
    }

    /** Client type, {@code client_id}, the served metadata, the mode, the login path. */
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientType", client.confidential() ? "confidential (private_key_jwt)"
                : "localhost development client (public)");
        m.put("clientId", client.clientId());
        m.put("redirectUri", client.redirectUri().toString());
        m.put("scope", client.scope());
        m.put("mode", config.mode().name().toLowerCase(java.util.Locale.ROOT));
        m.put("loginPath", underRoot(config.loginPath()));
        m.put("metadata", new LinkedHashMap<>(ClientMetadata.document(client, config)));
        m.put("sessionsListed", inMemory() != null);
        return m;
    }

    /**
     * The active sessions (within {@code session.max-age}), newest first (in-memory store only: an application's store is not enumerable): DID,
     * handle, PDS, scope, access token expiry, sign-in time.
     */
    public List<Map<String, Object>> sessions() {
        InMemoryAtprotoSessionStore store = inMemory();
        if (store == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        java.time.Instant now = java.time.Instant.now();
        store.all().stream().filter(s -> s.created().plus(config.session().maxAge()).isAfter(now)).sorted(Comparator.comparing(AtprotoSession::created).reversed()).limit(LIMIT)
                .forEach(s -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("did", s.did().value());
                    row.put("handle", s.handle().value());
                    row.put("pds", s.pds().toString());
                    row.put("scope", s.grantedScope());
                    row.put("accessExpiry", s.accessExpiry().toString());
                    row.put("created", s.created().toString());
                    out.add(row);
                });
        return out;
    }

    private InMemoryAtprotoSessionStore inMemory() {
        AtprotoSessionStore s = stores.blockingSessions();
        return s instanceof InMemoryAtprotoSessionStore m ? m : null;
    }

    private static String underRoot(String path) {
        String root = ConfigProvider.getConfig().getOptionalValue("quarkus.http.root-path", String.class).orElse("/");
        String p = path.startsWith("/") ? path : "/" + path;
        return (root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + p;
    }
}
