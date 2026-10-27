package org.sitenetsoft.atproto.lexicon.codegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles source files against the test classpath, keeping the class files in memory. */
final class InMemoryCompiler {
  private InMemoryCompiler() {
  }

  /** @return a class loader over the compiled classes; throws with the diagnostics on failure */
  static ClassLoader compile(List<Path> sources) throws IOException {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    Map<String, ByteArrayOutputStream> classes = new HashMap<>();
    try (StandardJavaFileManager std = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
      JavaFileManager fm = new ForwardingJavaFileManager<>(std) {
        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                   FileObject sibling) {
          return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension),
              kind) {
            @Override
            public OutputStream openOutputStream() {
              ByteArrayOutputStream bytes = new ByteArrayOutputStream();
              classes.put(className, bytes);
              return bytes;
            }
          };
        }
      };
      StringWriter log = new StringWriter();
      boolean ok = javac.getTask(log, fm, diagnostics,
          List.of("-classpath", System.getProperty("java.class.path"), "-Xlint:all", "-Werror",
              "-Xlint:-processing", "-proc:none"),
          null, std.getJavaFileObjectsFromPaths(sources)).call();
      if (!ok) {
        StringBuilder sb = new StringBuilder("compilation failed:\n").append(log);
        diagnostics.getDiagnostics().forEach(d -> sb.append(d).append('\n'));
        throw new AssertionError(sb.toString());
      }
    }
    return new ClassLoader(InMemoryCompiler.class.getClassLoader()) {
      @Override
      protected Class<?> findClass(String name) throws ClassNotFoundException {
        ByteArrayOutputStream bytes = classes.get(name);
        if (bytes == null) {
          throw new ClassNotFoundException(name);
        }
        byte[] b = bytes.toByteArray();
        return defineClass(name, b, 0, b.length);
      }
    };
  }
}
