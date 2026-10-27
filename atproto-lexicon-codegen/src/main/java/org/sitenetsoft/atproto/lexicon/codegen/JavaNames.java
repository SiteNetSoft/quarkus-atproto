package org.sitenetsoft.atproto.lexicon.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.SourceVersion;
import org.sitenetsoft.atproto.syntax.Nsid;

/**
 * NSID and Lexicon name to Java name rules.
 *
 * <ul>
 *   <li>package: the optional prefix, then the NSID authority segments in NSID order ({@code site.standard.document}
 *       → {@code site.standard}); each segment lowercased, {@code -} → {@code _}, a leading digit or a Java keyword
 *       escaped with {@code _}.</li>
 *   <li>class: the name segment with its first letter uppercased ({@code createRecord} → {@code CreateRecord}).</li>
 *   <li>nested types: the def or property name with its first letter uppercased.</li>
 *   <li>members: the property name as-is; Java keywords and the names a record component may not take
 *       ({@code hashCode}, {@code toString}, ...) get a trailing {@code _}.</li>
 *   <li>constants: UPPER_SNAKE of the camelCase name, other characters → {@code _}.</li>
 *   <li>per-namespace XRPC client: the last authority segment, capitalised, plus {@code Client}
 *       ({@code com.atproto.repo.*} → {@code com.atproto.repo.RepoClient}).</li>
 * </ul>
 */
public final class JavaNames {
  private static final Set<String> RECORD_FORBIDDEN = Set.of("clone", "finalize", "getClass", "hashCode", "notify",
      "notifyAll", "toString", "wait");

  private JavaNames() {
  }

  public static String packageName(Nsid nsid, Optional<String> prefix) {
    List<String> parts = new ArrayList<>();
    prefix.filter(p -> !p.isBlank()).ifPresent(parts::add);
    for (String seg : nsid.authority().split("\\.")) {
      parts.add(packageSegment(seg));
    }
    return String.join(".", parts);
  }

  public static String className(Nsid nsid) {
    return typeName(nsid.name());
  }

  public static String clientClassName(Nsid nsid) {
    String authority = nsid.authority();
    return typeName(authority.substring(authority.lastIndexOf('.') + 1)) + "Client";
  }

  /** A nested type name from a def or property name. */
  public static String typeName(String name) {
    String s = identifier(name);
    s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
    return SourceVersion.isKeyword(s) ? s + "_" : s;
  }

  /** A field, record component, parameter or method name. */
  public static String memberName(String name) {
    String s = identifier(name);
    return SourceVersion.isKeyword(s) || RECORD_FORBIDDEN.contains(s) ? s + "_" : s;
  }

  public static String constantName(String value) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isLetterOrDigit(c) && c < 128) {
        if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(value.charAt(i - 1))) {
          sb.append('_');
        }
        sb.append(Character.toUpperCase(c));
      } else {
        sb.append('_');
      }
    }
    String s = sb.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
    if (s.isEmpty()) {
      s = "VALUE";
    }
    return Character.isDigit(s.charAt(0)) ? "_" + s : s;
  }

  private static String packageSegment(String seg) {
    String s = seg.toLowerCase(Locale.ROOT).replace('-', '_');
    if (Character.isDigit(s.charAt(0))) {
      s = "_" + s;
    }
    return SourceVersion.isKeyword(s) ? s + "_" : s;
  }

  private static String identifier(String name) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean part = Character.isJavaIdentifierPart(c) && c != '$';
      if (i == 0 && !(Character.isJavaIdentifierStart(c) && c != '$')) {
        sb.append('_');
        if (part) {
          sb.append(c);
        }
      } else {
        sb.append(part ? c : '_');
      }
    }
    return sb.isEmpty() ? "_" : sb.toString();
  }
}
