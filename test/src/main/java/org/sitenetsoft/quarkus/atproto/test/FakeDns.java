package org.sitenetsoft.quarkus.atproto.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A DNS server on loopback UDP that answers {@code TXT} questions from a map, for applications that cannot replace
 * their {@code TxtResolver} bean: a packaged application under {@code @QuarkusIntegrationTest} (normal launch mode,
 * where {@code quarkus.atproto.identity.https-well-known-override} is ignored). Point the application at it with
 * {@code quarkus.atproto.identity.dns.servers} = {@link #server()}.
 * <p>
 * Names without a record answer {@code NXDOMAIN}; other record types answer with no records. Plain JDK, one daemon
 * thread; no EDNS, no TCP, no compression in questions (what DNS clients send).
 */
public final class FakeDns implements AutoCloseable {

  private static final int TXT = 16;
  private static final int CLASS_IN = 1;

  private final Map<String, List<String>> txt = new ConcurrentHashMap<>();
  private final DatagramSocket socket;
  private final Thread thread;

  private FakeDns(int port) {
    try {
      socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    } catch (SocketException e) {
      throw new UncheckedIOException("cannot bind the fake DNS server to port " + port, e);
    }
    thread = new Thread(this::serve, "fake-dns-" + socket.getLocalPort());
    thread.setDaemon(true);
    thread.start();
  }

  /** Starts on a free loopback UDP port ({@code 0}) or the given one. */
  public static FakeDns start(int port) {
    return new FakeDns(port);
  }

  /** Answers {@code TXT} questions for {@code name} with {@code values} (one string each). */
  public FakeDns txt(String name, String... values) {
    txt.put(normalise(name), List.of(values));
    return this;
  }

  /** {@code _atproto.<handle>} → {@code did=<did>}: the account's DNS handle proof. */
  public FakeDns handle(FakeAccount account) {
    return txt("_atproto." + account.handle().value(), "did=" + account.did().value());
  }

  /** The value for {@code quarkus.atproto.identity.dns.servers}: {@code 127.0.0.1:<port>}. */
  public String server() {
    return socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
  }

  public int port() {
    return socket.getLocalPort();
  }

  @Override
  public void close() {
    socket.close();
  }

  private static String normalise(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
  }

  private void serve() {
    byte[] buffer = new byte[1500];
    while (!socket.isClosed()) {
      DatagramPacket in = new DatagramPacket(buffer, buffer.length);
      try {
        socket.receive(in);
        byte[] answer = answer(buffer, in.getLength());
        if (answer != null) {
          socket.send(new DatagramPacket(answer, answer.length, in.getSocketAddress()));
        }
      } catch (IOException e) {
        if (socket.isClosed()) {
          return;
        }
      } catch (RuntimeException e) {
        // a malformed query: ignore it, keep serving
      }
    }
  }

  /** The response to one query, or {@code null} for something that is not a single-question query. */
  byte[] answer(byte[] q, int length) {
    if (length < 12 || (q[2] & 0x80) != 0 || ((q[4] & 0xff) << 8 | (q[5] & 0xff)) != 1) {
      return null;
    }
    StringBuilder name = new StringBuilder();
    int i = 12;
    while (true) {
      int len = q[i] & 0xff;
      i++;
      if (len == 0) {
        break;
      }
      if (len > 63 || i + len > length) {
        return null;
      }
      if (!name.isEmpty()) {
        name.append('.');
      }
      name.append(new String(q, i, len, StandardCharsets.US_ASCII));
      i += len;
    }
    int type = (q[i] & 0xff) << 8 | (q[i + 1] & 0xff);
    int questionEnd = i + 4;
    List<String> values = txt.get(normalise(name.toString()));
    boolean known = values != null;
    List<String> answers = known && type == TXT ? values : List.of();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(q[0]);
    out.write(q[1]);
    // QR=1, opcode 0, AA=1, RD copied; RA=1, rcode NXDOMAIN (3) when the name is unknown
    out.write(0x84 | (q[2] & 0x01));
    out.write(0x80 | (known ? 0 : 3));
    writeShort(out, 1);
    writeShort(out, answers.size());
    writeShort(out, 0);
    writeShort(out, 0);
    out.write(q, 12, questionEnd - 12);
    for (String value : answers) {
      byte[] text = value.getBytes(StandardCharsets.UTF_8);
      if (text.length > 255) {
        throw new IllegalStateException("TXT string longer than 255 bytes");
      }
      writeShort(out, 0xC00C); // pointer to the question name
      writeShort(out, TXT);
      writeShort(out, CLASS_IN);
      writeShort(out, 0);
      writeShort(out, 0); // TTL 0
      writeShort(out, text.length + 1);
      out.write(text.length);
      out.write(text, 0, text.length);
    }
    return out.toByteArray();
  }

  private static void writeShort(ByteArrayOutputStream out, int v) {
    out.write((v >> 8) & 0xff);
    out.write(v & 0xff);
  }
}
