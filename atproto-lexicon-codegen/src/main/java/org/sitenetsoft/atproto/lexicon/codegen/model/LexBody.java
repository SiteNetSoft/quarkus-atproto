package org.sitenetsoft.atproto.lexicon.codegen.model;

/**
 * The {@code input} or {@code output} of an XRPC method: a MIME {@code encoding} and, for JSON bodies, an optional
 * {@code schema} (an {@link LexObject}, {@link LexRef} or {@link LexUnion}; {@code null} when absent).
 */
public record LexBody(String description, String encoding, LexDef schema) {
}
