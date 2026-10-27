package org.sitenetsoft.atproto.syntax;

import java.util.Optional;

/** {@code at://AUTHORITY[/COLLECTION[/RKEY]]}. No query, fragment, port, userinfo or trailing slash. */
public final class AtUri {
  private final AtIdentifier authority;
  private final Nsid collection;
  private final RecordKey rkey;

  private AtUri(AtIdentifier authority, Nsid collection, RecordKey rkey) {
    this.authority = authority;
    this.collection = collection;
    this.rkey = rkey;
  }

  public static AtUri of(AtIdentifier authority, Nsid collection, RecordKey rkey) {
    if (authority == null || collection == null || rkey == null) {
      throw new SyntaxException("AT-URI parts must not be null");
    }
    return new AtUri(authority, collection, rkey);
  }

  public static AtUri of(AtIdentifier authority, Nsid collection) {
    if (authority == null || collection == null) {
      throw new SyntaxException("AT-URI parts must not be null");
    }
    return new AtUri(authority, collection, null);
  }

  public static AtUri of(AtIdentifier authority) {
    if (authority == null) {
      throw new SyntaxException("AT-URI parts must not be null");
    }
    return new AtUri(authority, null, null);
  }

  /** Parses; a handle authority must pass {@link Handle#parse} (syntax and the disallowed-TLD policy). */
  public static AtUri parse(String s) {
    return parse(s, AtIdentifier::parse);
  }

  /** Parses with a handle authority checked for syntax only ({@link Handle#parseSyntax}), for data read back. */
  public static AtUri parseSyntax(String s) {
    return parse(s, AtIdentifier::parseSyntax);
  }

  /** Whether {@link #parseSyntax} accepts {@code s}. */
  public static boolean isValidSyntax(String s) {
    try {
      parseSyntax(s);
      return true;
    } catch (SyntaxException e) {
      return false;
    }
  }

  private static AtUri parse(String s, java.util.function.Function<String, AtIdentifier> identifier) {
    if (s == null || s.length() > 8192 || !s.startsWith("at://")) {
      throw new SyntaxException("Invalid AT-URI: " + Did.abbreviate(s));
    }
    String[] parts = s.substring(5).split("/", -1);
    if (parts.length > 3) {
      throw new SyntaxException("Invalid AT-URI (too many path segments): " + Did.abbreviate(s));
    }
    try {
      AtIdentifier authority = identifier.apply(parts[0]);
      Nsid collection = parts.length > 1 ? Nsid.parse(parts[1]) : null;
      RecordKey rkey = parts.length > 2 ? RecordKey.parse(parts[2]) : null;
      return new AtUri(authority, collection, rkey);
    } catch (SyntaxException e) {
      throw new SyntaxException("Invalid AT-URI: " + Did.abbreviate(s) + " (" + e.getMessage() + ")");
    }
  }

  public static boolean isValid(String s) {
    try {
      parse(s);
      return true;
    } catch (SyntaxException e) {
      return false;
    }
  }

  public AtIdentifier authority() {
    return authority;
  }

  public Optional<Nsid> collection() {
    return Optional.ofNullable(collection);
  }

  public Optional<RecordKey> rkey() {
    return Optional.ofNullable(rkey);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof AtUri u && u.toString().equals(toString());
  }

  @Override
  public int hashCode() {
    return toString().hashCode();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("at://").append(authority.value());
    if (collection != null) {
      sb.append('/').append(collection.value());
      if (rkey != null) {
        sb.append('/').append(rkey.value());
      }
    }
    return sb.toString();
  }
}
