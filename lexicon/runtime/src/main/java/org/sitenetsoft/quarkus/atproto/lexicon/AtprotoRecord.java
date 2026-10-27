package org.sitenetsoft.quarkus.atproto.lexicon;

/**
 * A Lexicon record type: every class generated for a {@code record} def implements it, and writes its {@code $type}.
 */
public interface AtprotoRecord {
  /** The record's collection NSID, written as {@code $type}. */
  String $type();
}
