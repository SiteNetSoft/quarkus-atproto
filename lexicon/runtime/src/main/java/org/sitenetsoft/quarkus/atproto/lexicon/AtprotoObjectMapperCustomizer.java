package org.sitenetsoft.quarkus.atproto.lexicon;

import jakarta.inject.Singleton;

import org.sitenetsoft.atproto.syntax.AtprotoSyntaxModule;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.jackson.ObjectMapperCustomizer;

/**
 * Teaches the Quarkus-managed {@link ObjectMapper} the {@code atproto-syntax} types ({@code Did}, {@code Datetime},
 * {@code Nsid}, ...) used by generated Lexicon classes.
 */
@Singleton
public class AtprotoObjectMapperCustomizer implements ObjectMapperCustomizer {
  @Override
  public void customize(ObjectMapper mapper) {
    mapper.registerModule(new AtprotoSyntaxModule());
  }
}
