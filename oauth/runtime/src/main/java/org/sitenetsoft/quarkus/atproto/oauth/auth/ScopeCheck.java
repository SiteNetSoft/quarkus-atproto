package org.sitenetsoft.quarkus.atproto.oauth.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Whether a granted scope (https://atproto.com/specs/permission) covers a write:
 * <ul>
 * <li>{@code repo:<nsid>} or {@code repo:*}, optional {@code ?action=create&action=update&action=delete} (no action:
 * all three), or the query form {@code repo?collection=<nsid>&collection=...&action=...};</li>
 * <li>{@code blob:<mime>} ({@code *}{@code /*}, {@code image/*}, {@code image/png}) or
 * {@code blob?accept=<mime>&accept=...};</li>
 * <li>{@code transition:generic} allows every {@code repo} and {@code blob} write.</li>
 * </ul>
 * A malformed scope string grants nothing. Permission sets ({@code include:}) are expanded by the authorization server
 * into the granted scope, so they never need expanding here.
 */
public final class ScopeCheck {

    private static final Set<String> ACTIONS = Set.of("create", "update", "delete");

    private ScopeCheck() {
    }

    /**
     * @param grantedScope the space-separated granted scope
     * @param resource {@code repo} or {@code blob}
     * @param target the collection NSID ({@code repo}) or the MIME type ({@code blob})
     * @param action for {@code repo}: {@code create}, {@code update} or {@code delete}; empty: any action
     * @throws IllegalArgumentException for another resource
     */
    public static boolean allows(String grantedScope, String resource, String target, Optional<String> action) {
        if (!"repo".equals(resource) && !"blob".equals(resource)) {
            throw new IllegalArgumentException("resource must be repo or blob, not " + resource);
        }
        if (grantedScope == null || grantedScope.isBlank() || target == null) {
            return false;
        }
        for (String scope : grantedScope.trim().split("\\s+")) {
            if (scope.equals("transition:generic")) {
                return true;
            }
            Parsed p = parse(scope);
            if (p == null || !p.resource().equals(resource)) {
                continue;
            }
            if (resource.equals("repo") ? repoAllows(p, target, action) : blobAllows(p, target)) {
                return true;
            }
        }
        return false;
    }

    private record Parsed(String resource, List<String[]> values) {
    }

    private static boolean repoAllows(Parsed p, String collection, Optional<String> action) {
        List<String> collections = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        for (String[] v : p.values()) {
            switch (v[0]) {
                case "", "collection" -> collections.add(v[1]);
                case "action" -> {
                    if (!ACTIONS.contains(v[1])) {
                        return false;
                    }
                    actions.add(v[1]);
                }
                default -> {
                    return false;
                }
            }
        }
        boolean collectionOk = collections.stream().anyMatch(c -> c.equals("*") || c.equals(collection));
        boolean actionOk = action.isEmpty() || actions.isEmpty() || actions.contains(action.get());
        return collectionOk && actionOk;
    }

    private static boolean blobAllows(Parsed p, String mimeType) {
        String mime = mimeType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        int slash = mime.indexOf('/');
        for (String[] v : p.values()) {
            if (!v[0].isEmpty() && !v[0].equals("accept")) {
                return false;
            }
        }
        for (String[] v : p.values()) {
            String a = v[1].toLowerCase(Locale.ROOT);
            if (a.equals("*/*") || a.equals(mime)
                    || (a.endsWith("/*") && slash > 0 && a.substring(0, a.length() - 1).equals(mime.substring(0, slash + 1)))) {
                return true;
            }
        }
        return false;
    }

    /** {@code resource[:positional][?k=v&...]}; the positional value has the key {@code ""}; null when malformed. */
    private static Parsed parse(String scope) {
        int q = scope.indexOf('?');
        String head = q < 0 ? scope : scope.substring(0, q);
        int colon = head.indexOf(':');
        String resource = colon < 0 ? head : head.substring(0, colon);
        List<String[]> values = new ArrayList<>();
        if (colon >= 0) {
            String positional = head.substring(colon + 1);
            if (positional.isEmpty()) {
                return null;
            }
            values.add(new String[] {"", decode(positional)});
        }
        if (q >= 0) {
            for (String pair : scope.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0 || eq == pair.length() - 1) {
                    return null;
                }
                values.add(new String[] {decode(pair.substring(0, eq)), decode(pair.substring(eq + 1))});
            }
        }
        return values.isEmpty() ? null : new Parsed(resource, values);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "\u0000"; // matches nothing
        }
    }
}
