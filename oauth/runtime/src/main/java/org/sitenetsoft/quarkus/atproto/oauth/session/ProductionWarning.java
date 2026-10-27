package org.sitenetsoft.quarkus.atproto.oauth.session;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import io.quarkus.runtime.LaunchMode;

/** WARNs once per store when an in-memory default is used in production (extension internal). */
public final class ProductionWarning {

    private static final Logger LOG = Logger.getLogger("org.sitenetsoft.quarkus.atproto.oauth");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ProductionWarning() {
    }

    public static void warnIfProduction(String store) {
        if (LaunchMode.current() != LaunchMode.NORMAL || !WARNED.add(store)) {
            return;
        }
        LOG.warnf("In-memory %s in production: its entries are lost on restart and not shared between nodes. "
                + "Provide an %s bean that persists them (with tokens and DPoP keys encrypted at rest).", store, store);
    }
}
