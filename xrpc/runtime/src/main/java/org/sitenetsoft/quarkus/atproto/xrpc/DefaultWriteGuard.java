package org.sitenetsoft.quarkus.atproto.xrpc;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import org.sitenetsoft.atproto.syntax.Nsid;

import io.quarkus.arc.DefaultBean;

/** Allows every write; replaced by any other {@link WriteGuard} bean. */
@DefaultBean
@ApplicationScoped
public class DefaultWriteGuard implements WriteGuard {
  @Override
  public void check(Nsid collection, String action, Optional<String> blobMime) {
    // no restriction
  }
}
