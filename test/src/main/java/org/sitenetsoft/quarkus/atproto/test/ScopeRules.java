package org.sitenetsoft.quarkus.atproto.test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * The OAuth scope rules of AT Protocol (https://atproto.com/specs/permission) as far as the fakes need them: a
 * space-separated list in which {@code atproto} is required by the authorization server; {@code repo:<nsid>} or
 * {@code repo:*} with optional {@code ?action=create&action=update&action=delete} (no action means all), or the query
 * form {@code repo?collection=...&action=...}; {@code blob:<mime>} ({@code &#42;/&#42;}, {@code image/*}, {@code image/png}) or
 * {@code blob?accept=...}; {@code transition:generic} grants every repo action and every blob. {@code account:},
 * {@code identity:}, {@code rpc:} and the other {@code transition:} scopes are accepted but grant nothing here.
 * {@code include:} (permission sets) is not supported and, like any unknown scope, is an {@code invalid_scope}.
 */
public final class ScopeRules {

  /** A malformed or unsupported scope; the authorization server answers {@code invalid_scope}. */
  public static final class InvalidScopeException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    InvalidScopeException(String message) {
      super(message);
    }
  }

  /** A repository action. */
  public enum Action {
    CREATE, UPDATE, DELETE;

    static Action parse(String s) {
      return switch (s) {
        case "create" -> CREATE;
        case "update" -> UPDATE;
        case "delete" -> DELETE;
        default -> throw new InvalidScopeException("Unknown repo action: " + s);
      };
    }
  }

  private record RepoGrant(String collection, Set<Action> actions) {
  }

  private static final Pattern MIME = Pattern.compile("(\\*/\\*)|([a-z0-9.+-]+/\\*)|([a-z0-9.+-]+/[a-z0-9.+-]+)");

  private final Set<String> scopes;
  private final boolean atproto;
  private final boolean generic;
  private final List<RepoGrant> repo;
  private final List<String> blob;

  private ScopeRules(Set<String> scopes, boolean atproto, boolean generic, List<RepoGrant> repo, List<String> blob) {
    this.scopes = Collections.unmodifiableSet(scopes);
    this.atproto = atproto;
    this.generic = generic;
    this.repo = List.copyOf(repo);
    this.blob = List.copyOf(blob);
  }

  /** Parses a space-separated scope string; throws {@link InvalidScopeException} for a malformed or unsupported one. */
  public static ScopeRules parse(String scope) {
    if (scope == null || scope.isBlank()) {
      throw new InvalidScopeException("Empty scope");
    }
    Set<String> scopes = new LinkedHashSet<>();
    boolean atproto = false;
    boolean generic = false;
    List<RepoGrant> repo = new ArrayList<>();
    List<String> blob = new ArrayList<>();
    for (String s : scope.trim().split(" +")) {
      scopes.add(s);
      String resource = resource(s);
      String positional = positional(s);
      List<String[]> params = params(s);
      switch (resource) {
        case "atproto" -> {
          requireBare(s, positional, params);
          atproto = true;
        }
        case "transition" -> {
          if (!params.isEmpty() || positional == null) {
            throw new InvalidScopeException("Invalid transition scope: " + s);
          }
          switch (positional) {
            case "generic" -> generic = true;
            case "chat.bsky", "email" -> {
              // valid, grants nothing the fakes check
            }
            default -> throw new InvalidScopeException("Unknown transition scope: " + s);
          }
        }
        case "repo" -> {
          List<String> collections = new ArrayList<>();
          Set<Action> actions = EnumSet.noneOf(Action.class);
          if (positional != null) {
            collections.add(positional);
          }
          for (String[] p : params) {
            switch (p[0]) {
              case "collection" -> collections.add(p[1]);
              case "action" -> actions.add(Action.parse(p[1]));
              default -> throw new InvalidScopeException("Unknown repo parameter in " + s);
            }
          }
          if (collections.isEmpty()) {
            throw new InvalidScopeException("repo scope without a collection: " + s);
          }
          for (String c : collections) {
            if (!c.equals("*") && !Nsid.isValid(c)) {
              throw new InvalidScopeException("Invalid collection in " + s);
            }
            repo.add(new RepoGrant(c, actions.isEmpty() ? EnumSet.allOf(Action.class) : actions));
          }
        }
        case "blob" -> {
          List<String> accept = new ArrayList<>();
          if (positional != null) {
            accept.add(positional);
          }
          for (String[] p : params) {
            if (!p[0].equals("accept")) {
              throw new InvalidScopeException("Unknown blob parameter in " + s);
            }
            accept.add(p[1]);
          }
          if (accept.isEmpty()) {
            throw new InvalidScopeException("blob scope without a MIME type: " + s);
          }
          for (String a : accept) {
            if (!MIME.matcher(a.toLowerCase(Locale.ROOT)).matches()) {
              throw new InvalidScopeException("Invalid MIME pattern in " + s);
            }
            blob.add(a.toLowerCase(Locale.ROOT));
          }
        }
        case "account", "identity", "rpc" -> {
          if (positional == null && params.isEmpty()) {
            throw new InvalidScopeException("Incomplete scope: " + s);
          }
        }
        case "include" -> throw new InvalidScopeException("Permission sets (include:) are not supported: " + s);
        default -> throw new InvalidScopeException("Unknown scope: " + s);
      }
    }
    return new ScopeRules(scopes, atproto, generic, repo, blob);
  }

  private static String resource(String s) {
    int end = s.length();
    int colon = s.indexOf(':');
    int q = s.indexOf('?');
    if (colon >= 0) {
      end = colon;
    }
    if (q >= 0 && q < end) {
      end = q;
    }
    return s.substring(0, end);
  }

  private static String positional(String s) {
    int colon = s.indexOf(':');
    int q = s.indexOf('?');
    if (colon < 0 || (q >= 0 && q < colon)) {
      return null;
    }
    String p = q < 0 ? s.substring(colon + 1) : s.substring(colon + 1, q);
    if (p.isEmpty()) {
      throw new InvalidScopeException("Empty positional value in " + s);
    }
    return URLDecoder.decode(p, StandardCharsets.UTF_8);
  }

  private static List<String[]> params(String s) {
    int q = s.indexOf('?');
    List<String[]> out = new ArrayList<>();
    if (q < 0) {
      return out;
    }
    for (String pair : s.substring(q + 1).split("&")) {
      int eq = pair.indexOf('=');
      if (eq <= 0 || eq == pair.length() - 1) {
        throw new InvalidScopeException("Malformed parameter in " + s);
      }
      out.add(new String[] {URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)});
    }
    return out;
  }

  private static void requireBare(String s, String positional, List<String[]> params) {
    if (positional != null || !params.isEmpty()) {
      throw new InvalidScopeException("Unexpected parameters in " + s);
    }
  }

  /** The individual scope strings, in request order. */
  public Set<String> scopes() {
    return scopes;
  }

  public boolean hasAtproto() {
    return atproto;
  }

  /** Whether {@code action} ({@code create}, {@code update}, {@code delete}) on {@code collection} is granted. */
  public boolean allowsRepo(Nsid collection, String action) {
    return allowsRepo(collection, Action.parse(action));
  }

  public boolean allowsRepo(Nsid collection, Action action) {
    if (generic) {
      return true;
    }
    for (RepoGrant g : repo) {
      if ((g.collection.equals("*") || g.collection.equals(collection.value())) && g.actions.contains(action)) {
        return true;
      }
    }
    return false;
  }

  /** Whether uploading a blob of {@code mimeType} is granted. */
  public boolean allowsBlob(String mimeType) {
    if (generic) {
      return true;
    }
    String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT).split(";")[0].trim();
    int slash = mime.indexOf('/');
    for (String a : blob) {
      if (a.equals("*/*") || a.equals(mime) || (a.endsWith("/*") && slash > 0
          && a.substring(0, a.length() - 1).equals(mime.substring(0, slash + 1)))) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return String.join(" ", scopes);
  }
}
