package org.sitenetsoft.atproto.syntax;

import java.util.Objects;

/**
 * The {@code key} type of a Lexicon record: {@code tid}, {@code nsid}, {@code any} or {@code literal:<value>}.
 * A final class rather than an enum because LITERAL carries its value.
 */
public final class RecordKeyType {
  public enum Kind { TID, NSID, LITERAL, ANY }

  public static final RecordKeyType TID = new RecordKeyType(Kind.TID, null);
  public static final RecordKeyType NSID = new RecordKeyType(Kind.NSID, null);
  public static final RecordKeyType ANY = new RecordKeyType(Kind.ANY, null);

  private final Kind kind;
  private final String literal;

  private RecordKeyType(Kind kind, String literal) {
    this.kind = kind;
    this.literal = literal;
  }

  public static RecordKeyType literal(String value) {
    return new RecordKeyType(Kind.LITERAL, RecordKey.parse(value).value());
  }

  public static RecordKeyType parse(String s) {
    if (s == null) {
      throw new SyntaxException("Invalid record key type: null");
    }
    switch (s) {
      case "tid": return TID;
      case "nsid": return NSID;
      case "any": return ANY;
      default:
        if (s.startsWith("literal:")) {
          return literal(s.substring("literal:".length()));
        }
        throw new SyntaxException("Invalid record key type: " + Did.abbreviate(s));
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

  public Kind kind() {
    return kind;
  }

  /** The literal value, or null unless the kind is LITERAL. */
  public String literalValue() {
    return literal;
  }

  public boolean accepts(RecordKey key) {
    return switch (kind) {
      case TID -> Tid.isValid(key.value());
      case NSID -> Nsid.isValid(key.value());
      case LITERAL -> literal.equals(key.value());
      case ANY -> true;
    };
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof RecordKeyType t && t.kind == kind && Objects.equals(t.literal, literal);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, literal);
  }

  @Override
  public String toString() {
    return switch (kind) {
      case TID -> "tid";
      case NSID -> "nsid";
      case ANY -> "any";
      case LITERAL -> "literal:" + literal;
    };
  }
}
