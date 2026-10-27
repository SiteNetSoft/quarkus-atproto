package org.sitenetsoft.quarkus.atproto.identity;

/**
 * Neither DNS ({@code _atproto.<handle>} TXT {@code did=}) nor HTTPS ({@code /.well-known/atproto-did}) gave a DID for
 * the handle.
 */
public class HandleNotFound extends IdentityException {

    public HandleNotFound(String handle, String message) {
        super(handle, message);
    }
}
