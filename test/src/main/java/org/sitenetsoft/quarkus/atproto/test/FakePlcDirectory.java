package org.sitenetsoft.quarkus.atproto.test;

import java.net.URI;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.sitenetsoft.atproto.syntax.Did;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * A PLC directory: {@code GET /<did>} answers the DID document of a {@link FakeAccount} ({@code id},
 * {@code alsoKnownAs: ["at://<handle>"]}, a {@code #atproto} {@code Multikey} verification method and a
 * {@code #atproto_pds} {@code AtprotoPersonalDataServer} service at the fake PDS), {@code 404} for unknown DIDs and
 * {@code 410} for DIDs marked with {@link #tombstone(Did)}.
 */
public final class FakePlcDirectory {

  private final FakeAtproto fake;
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private final Set<String> tombstones = ConcurrentHashMap.newKeySet();
  private final Map<String, URI> movedPds = new ConcurrentHashMap<>();
  private volatile boolean unavailable;

  FakePlcDirectory(FakeAtproto fake) {
    this.fake = fake;
  }

  Router router(io.vertx.core.Vertx vertx) {
    Router router = Http.router(vertx, requests);
    router.get("/:did").handler(this::document);
    return router;
  }

  private void document(RoutingContext ctx) {
    String did = ctx.pathParam("did");
    if (unavailable) {
      Http.json(ctx, 503, Map.of("message", "Service Unavailable"));
      return;
    }
    if (tombstones.contains(did)) {
      Http.json(ctx, 410, Map.of("message", "DID not available: " + did));
      return;
    }
    Optional<FakeAccount> account = fake.findAccount(did);
    if (account.isEmpty()) {
      Http.json(ctx, 404, Map.of("message", "DID not registered: " + did));
      return;
    }
    Http.json(ctx, 200, document(account.get()));
  }

  /** The DID document served for {@code account}. */
  public Map<String, Object> document(FakeAccount account) {
    String did = account.did().value();
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("@context", List.of("https://www.w3.org/ns/did/v1", "https://w3id.org/security/multikey/v1"));
    doc.put("id", did);
    doc.put("alsoKnownAs", List.of("at://" + account.handle().value()));
    Map<String, Object> vm = new LinkedHashMap<>();
    vm.put("id", did + "#atproto");
    vm.put("type", "Multikey");
    vm.put("controller", did);
    vm.put("publicKeyMultibase", account.publicKeyMultibase());
    doc.put("verificationMethod", List.of(vm));
    Map<String, Object> service = new LinkedHashMap<>();
    service.put("id", "#atproto_pds");
    service.put("type", "AtprotoPersonalDataServer");
    service.put("serviceEndpoint", movedPds.getOrDefault(did, fake.pdsUrl()).toString());
    doc.put("service", List.of(service));
    return doc;
  }

  /**
   * From now on {@code did}'s document names {@code pds} as its {@code #atproto_pds} (an account migration; until
   * {@link FakeAtproto#clear()}).
   */
  public void movePds(Did did, URI pds) {
    movedPds.put(did.value(), pds);
  }

  /** While {@code true} every document request answers {@code 503} (an outage; until {@link FakeAtproto#clear()}). */
  public void unavailable(boolean value) {
    unavailable = value;
  }

  /** From now on {@code did} answers {@code 410 Gone}. */
  public void tombstone(Did did) {
    tombstones.add(did.value());
  }

  public List<Recorded> requests() {
    return List.copyOf(requests);
  }

  void clear() {
    tombstones.clear();
    movedPds.clear();
    unavailable = false;
    requests.clear();
  }
}
