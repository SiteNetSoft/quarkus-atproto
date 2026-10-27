package org.sitenetsoft.atproto.lexicon.codegen.model;

/** A Lexicon schema definition or field type. */
public sealed interface LexDef permits LexRecord, LexObject, LexQuery, LexProcedure, LexSubscription, LexToken,
    LexString, LexInteger, LexBoolean, LexBytes, LexCidLink, LexBlob, LexArray, LexRef, LexUnion, LexUnknown,
    LexParams, LexPermissionSet {
  /** The {@code description}, or {@code null}. */
  String description();

  /** The Lexicon {@code type} name. */
  String type();
}
