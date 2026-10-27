package org.sitenetsoft.quarkus.atproto.oauth.auth;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcAuth;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcClient;

/** An XRPC client whose calls without an explicit {@link XrpcAuth} are authorized with a session's DPoP auth. */
public final class SessionXrpc {

    private SessionXrpc() {
    }

    public static XrpcClient client(XrpcClient delegate, XrpcAuth session) {
        return new XrpcClient() {
            @Override
            public <T> T query(URI service, Nsid nsid, Map<String, ?> params, Class<T> out, Optional<XrpcAuth> auth) {
                return delegate.query(service, nsid, params, out, or(auth, session));
            }

            @Override
            public <T> T procedure(URI service, Nsid nsid, Object input, Class<T> out, Optional<XrpcAuth> auth) {
                return delegate.procedure(service, nsid, input, out, or(auth, session));
            }

            @Override
            public <T> T procedureBytes(URI service, Nsid nsid, byte[] body, String contentType, Class<T> out,
                    Optional<XrpcAuth> auth) {
                return delegate.procedureBytes(service, nsid, body, contentType, out, or(auth, session));
            }
        };
    }

    private static Optional<XrpcAuth> or(Optional<XrpcAuth> given, XrpcAuth session) {
        return given != null && given.isPresent() ? given : Optional.of(session);
    }
}
