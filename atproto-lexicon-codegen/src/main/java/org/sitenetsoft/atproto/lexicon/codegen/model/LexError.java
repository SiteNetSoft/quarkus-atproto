package org.sitenetsoft.atproto.lexicon.codegen.model;

/** A named error an XRPC method may return. */
public record LexError(String name, String description) {
}
