package org.sitenetsoft.quarkus.atproto.xrpc.api;

import io.smallrye.mutiny.Uni;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * The generic XRPC client the generated per-namespace clients ({@code com.atproto.repo.RepoClient}) call. Bodies are
 * JSON written and read with Jackson; a non-2xx answer fails the {@link Uni} with an {@link XrpcException}.
 */
public interface ReactiveXrpcClient {
  /**
   * {@code GET <service>/xrpc/<nsid>?<params>}.
   *
   * @param params query parameters; {@code null} values are left out, collections repeat the parameter
   */
  <T> Uni<T> query(URI service, Nsid nsid, Map<String, ?> params, Class<T> out, Optional<XrpcAuth> auth);

  /** {@code POST <service>/xrpc/<nsid>} with {@code input} as the JSON body ({@code null}: no body). */
  <T> Uni<T> procedure(URI service, Nsid nsid, Object input, Class<T> out, Optional<XrpcAuth> auth);

  /** {@code POST <service>/xrpc/<nsid>} with a raw body of {@code contentType} (e.g. a blob upload). */
  <T> Uni<T> procedureBytes(URI service, Nsid nsid, byte[] body, String contentType, Class<T> out,
                            Optional<XrpcAuth> auth);
}
