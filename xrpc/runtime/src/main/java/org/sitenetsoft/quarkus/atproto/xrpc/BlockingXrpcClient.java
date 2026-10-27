package org.sitenetsoft.quarkus.atproto.xrpc;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.sitenetsoft.atproto.syntax.Nsid;
import org.sitenetsoft.quarkus.atproto.xrpc.api.ReactiveXrpcClient;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcAuth;
import org.sitenetsoft.quarkus.atproto.xrpc.api.XrpcClient;
import org.sitenetsoft.quarkus.safefetch.FetchTimeoutException;

import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;

/** Blocking facade over {@link XrpcClientImpl}. Never call it on the event loop. */
@ApplicationScoped
public class BlockingXrpcClient implements XrpcClient {

  private final ReactiveXrpcClient delegate;
  private final Duration limit;

  @Inject
  public BlockingXrpcClient(ReactiveXrpcClient delegate, XrpcConfig config) {
    this.delegate = delegate;
    this.limit = awaitLimit(config);
  }

  /** Two exchanges (the authorization retry) plus slack for the authorizer (a token refresh). */
  static Duration awaitLimit(XrpcConfig config) {
    return config.timeout().multipliedBy(2).plusSeconds(10);
  }

  @Override
  public <T> T query(URI service, Nsid nsid, Map<String, ?> params, Class<T> out, Optional<XrpcAuth> auth) {
    return await(nsid, delegate.query(service, nsid, params, out, auth), limit);
  }

  @Override
  public <T> T procedure(URI service, Nsid nsid, Object input, Class<T> out, Optional<XrpcAuth> auth) {
    return await(nsid, delegate.procedure(service, nsid, input, out, auth), limit);
  }

  @Override
  public <T> T procedureBytes(URI service, Nsid nsid, byte[] body, String contentType, Class<T> out,
      Optional<XrpcAuth> auth) {
    return await(nsid, delegate.procedureBytes(service, nsid, body, contentType, out, auth), limit);
  }

  static <T> T await(Object what, Uni<T> uni, Duration limit) {
    try {
      return uni.await().atMost(limit);
    } catch (TimeoutException e) {
      throw new FetchTimeoutException("XRPC " + what + " did not complete within " + limit, e);
    }
  }
}
