package org.sitenetsoft.quarkus.atproto.xrpc.api;

import io.smallrye.mutiny.Uni;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.sitenetsoft.atproto.syntax.Nsid;

/** Test stub of the type defined in quarkus-atproto-xrpc (Task 10); signatures per the controller ruling. */
public interface ReactiveXrpcClient {
  <T> Uni<T> query(URI service, Nsid nsid, Map<String, ?> params, Class<T> out, Optional<XrpcAuth> auth);

  <T> Uni<T> procedure(URI service, Nsid nsid, Object input, Class<T> out, Optional<XrpcAuth> auth);

  <T> Uni<T> procedureBytes(URI service, Nsid nsid, byte[] body, String contentType, Class<T> out,
                            Optional<XrpcAuth> auth);
}
