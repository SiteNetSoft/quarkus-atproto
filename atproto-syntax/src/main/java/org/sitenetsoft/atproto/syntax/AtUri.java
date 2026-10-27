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
    return new AtUri(authority, collection, null);
  }

  public static AtUri of(AtIdentifier authority) {
    return new AtUri(authority, null, null);
  }

  public static AtUri parse(String s) {
    if (s == null || s.length() > 8192 || !s.startsWith("at://")) {
      throw new SyntaxException("Invalid AT-URI: " + Did.abbreviate(s));
    }
    String[] parts = s.substring(5).split("/", -1);
    if (parts.length > 3) {
      throw new SyntaxException("Invalid AT-URI (too many path segments): " + Did.abbreviate(s));
    }
    try {
      AtIdentifier authority = AtIdentifier.parse(parts[0]);
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
