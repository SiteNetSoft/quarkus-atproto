package org.sitenetsoft.quarkus.atproto.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.sitenetsoft.atproto.syntax.Did;
import org.sitenetsoft.atproto.syntax.Handle;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

/**
 * A fake AT Protocol network on loopback ({@code http://127.0.0.1:<port>}): a {@link FakePlcDirectory}, a
 * {@link FakeAuthorizationServer} (its {@code issuer} is its own origin) and a {@link FakePds}, sharing the accounts
 * made with {@link #account(String)}. Plain Vert.x, no Quarkus.
 * <p>
 * Point the app under test at it with {@code quarkus.did.plc.url} = {@link #plcUrl()} and, per handle,
 * {@code quarkus.atproto.identity.https-well-known-override."<handle>"} = {@link #wellKnownBase(String)} (DNS is
 * replaced by the test's own {@code TxtResolver}); safe-fetch must allow {@code http} and loopback addresses.
 * <p>
 * <b>QuarkusUnitTest</b> loads a test class twice (two class loaders), so a static initialiser runs twice. Use
 * {@link #reservePorts(String)} (the ports are kept in a system property, so both loads see the same ones) and
 * {@link #start(int, int, int)}: a second start on the same ports in the same JVM stops the first instance and takes
 * over, so the instance the test methods see is the one serving. Accounts get DIDs derived from their handle, so both
 * loads agree on them.
 */
public final class FakeAtproto implements AutoCloseable {

  private static final String REGISTRY_PREFIX = FakeAtproto.class.getName() + ".running.";

  private final Map<String, FakeAccount> accountsByHandle = new ConcurrentHashMap<>();
  private final List<FakeAccount> accountOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final FakePlcDirectory plc = new FakePlcDirectory(this);
  private final FakeAuthorizationServer as = new FakeAuthorizationServer(this);
  private final FakePds pds = new FakePds(this);
  private Vertx vertx;
  private URI plcUrl;
  private URI asUrl;
  private URI pdsUrl;
  private String registryKey;

  private FakeAtproto() {
  }

  /** Starts the three servers on random free ports. */
  public static FakeAtproto start() {
    return start(0, 0, 0);
  }

  /**
   * Starts the three servers on the given ports ({@code 0}: random). If another {@code FakeAtproto} (from any class
   * loader) is serving exactly these non-zero ports in this JVM, it is stopped first.
   */
  public static FakeAtproto start(int plcPort, int asPort, int pdsPort) {
    FakeAtproto fake = new FakeAtproto();
    if (plcPort != 0 && asPort != 0 && pdsPort != 0) {
      fake.registryKey = REGISTRY_PREFIX + plcPort + "," + asPort + "," + pdsPort;
      synchronized (System.getProperties()) {
        Object previous = System.getProperties().remove(fake.registryKey);
        if (previous instanceof AutoCloseable c) {
          try {
            c.close();
          } catch (Exception e) {
            // the previous instance is gone either way
          }
        }
      }
    }
    fake.vertx = Vertx.vertx();
    try {
      fake.plcUrl = fake.listen(fake.plc.router(fake.vertx), plcPort);
      fake.asUrl = fake.listen(fake.as.router(fake.vertx), asPort);
      fake.pdsUrl = fake.listen(fake.pds.router(fake.vertx), pdsPort);
    } catch (RuntimeException e) {
      fake.close();
      throw e;
    }
    if (fake.registryKey != null) {
      System.getProperties().put(fake.registryKey, fake);
    }
    return fake;
  }

  private URI listen(io.vertx.ext.web.Router router, int port) {
    HttpServer server = vertx.createHttpServer().requestHandler(router);
    int actual = server.listen(port, "127.0.0.1").toCompletionStage().toCompletableFuture().join().actualPort();
    return URI.create("http://127.0.0.1:" + actual);
  }

  /**
   * Three free loopback ports, reserved once per JVM under the system property {@code key} (as
   * {@code "plc,as,pds"}) so every class loader gets the same ones.
   */
  public static int[] reservePorts(String key) {
    synchronized (System.getProperties()) {
      String existing = System.getProperty(key);
      if (existing == null) {
        existing = freePort() + "," + freePort() + "," + freePort();
        System.setProperty(key, existing);
      }
      String[] parts = existing.split(",");
      if (parts.length != 3) {
        throw new IllegalStateException("System property " + key + " is not plc,as,pds ports: " + existing);
      }
      return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
          Integer.parseInt(parts[2].trim())};
    }
  }

  /** A currently free loopback port. */
  public static int freePort() {
    try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      s.setReuseAddress(true);
      return s.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Stops the servers. */
  @Override
  public synchronized void close() {
    if (registryKey != null) {
      synchronized (System.getProperties()) {
        System.getProperties().remove(registryKey, this);
      }
    }
    if (vertx != null) {
      Vertx v = vertx;
      vertx = null;
      v.close().toCompletionStage().toCompletableFuture().join();
    }
  }

  // ---- accounts ---------------------------------------------------------------------------------------------------

  /** The account for {@code handle}, created on first use (its DID is derived from the handle). */
  public FakeAccount account(String handle) {
    Handle h = Handle.parse(handle.toLowerCase(Locale.ROOT));
    return accountsByHandle.computeIfAbsent(h.value(), x -> {
      FakeAccount a = new FakeAccount(h);
      accountOrder.add(a);
      return a;
    });
  }

  /** An existing account by handle or DID. */
  public Optional<FakeAccount> findAccount(String handleOrDid) {
    if (handleOrDid == null) {
      return Optional.empty();
    }
    if (handleOrDid.startsWith("did:")) {
      return accountOrder.stream().filter(a -> a.did().value().equals(handleOrDid)).findFirst();
    }
    String h = handleOrDid.toLowerCase(Locale.ROOT);
    if (h.startsWith("@")) {
      h = h.substring(1);
    }
    return Optional.ofNullable(accountsByHandle.get(h));
  }

  public Optional<FakeAccount> findAccount(Did did) {
    return findAccount(did.value());
  }

  /** Accounts in creation order. */
  public List<FakeAccount> accounts() {
    return new ArrayList<>(accountOrder);
  }

  // ---- URLs and servers -------------------------------------------------------------------------------------------

  public URI plcUrl() {
    return plcUrl;
  }

  /** The authorization server's origin, which is also its {@code issuer}. */
  public URI asUrl() {
    return asUrl;
  }

  public URI pdsUrl() {
    return pdsUrl;
  }

  /**
   * The base URL whose {@code /.well-known/atproto-did} answers for {@code handle}: the value for
   * {@code quarkus.atproto.identity.https-well-known-override."<handle>"}.
   */
  public URI wellKnownBase(String handle) {
    return URI.create(pdsUrl + "/wk/" + handle.toLowerCase(Locale.ROOT));
  }

  public FakePlcDirectory plc() {
    return plc;
  }

  public FakeAuthorizationServer as() {
    return as;
  }

  public FakePds pds() {
    return pds;
  }

  /** Resets knobs, counters, tokens, records and recorded requests; accounts stay. */
  public void clear() {
    plc.clear();
    as.clear();
    pds.clear();
  }
}
