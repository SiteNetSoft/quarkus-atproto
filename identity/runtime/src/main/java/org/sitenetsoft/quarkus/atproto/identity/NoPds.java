package org.sitenetsoft.quarkus.atproto.identity;

/**
 * The DID document has no usable {@code #atproto_pds} service: missing, not of type
 * {@code AtprotoPersonalDataServer}, or an endpoint that is not a bare {@code https://host[:port]} URL.
 */
public class NoPds extends IdentityException {

    public NoPds(String did, String message) {
        super(did, message);
    }
}
