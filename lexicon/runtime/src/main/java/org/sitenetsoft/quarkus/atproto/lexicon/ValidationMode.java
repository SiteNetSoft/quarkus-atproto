package org.sitenetsoft.quarkus.atproto.lexicon;

/**
 * How strictly records are checked against their Lexicon, mirroring {@code com.atproto.repo.createRecord}'s
 * {@code validate} parameter.
 */
public enum ValidationMode {
  /** Validate; a record whose collection Lexicon is not known fails. */
  TRUE,
  /** Do not validate. */
  FALSE,
  /** Validate when the collection Lexicon is known, accept the record otherwise (the default). */
  OPTIMISTIC
}
