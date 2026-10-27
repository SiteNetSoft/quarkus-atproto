package org.sitenetsoft.quarkus.atproto.xrpc.api;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.sitenetsoft.atproto.syntax.Nsid;

/** Blocking twin of {@link ReactiveXrpcClient}; throws {@link XrpcException} on a non-2xx answer. */
public interface XrpcClient {
  <T> T query(URI service, Nsid nsid, Map<String, ?> params, Class<T> out, Optional<XrpcAuth> auth);

  <T> T procedure(URI service, Nsid nsid, Object input, Class<T> out, Optional<XrpcAuth> auth);

  <T> T procedureBytes(URI service, Nsid nsid, byte[] body, String contentType, Class<T> out,
                       Optional<XrpcAuth> auth);
}
