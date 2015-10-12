package io.vertx.codegen.cli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import io.vertx.codegen.CodeGenProcessor;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class Main {

  public static void main(String[] args) throws Exception {
    Main main = new Main();
    main.run(args);
  }

  @Parameter(description = "The list of maven artifact coordinates to generate")
  private final List<String> parameters = new ArrayList<>();

  @Parameter(names = {"--codegen"}, description = "the codegen.js file path")
  private String codegen;

  @Parameter(names = {"--target"}, description = "the root directory for generated files")
  private String target;

  public void run(String[] args) throws Exception {

    JCommander jc = new JCommander(this, args);

    //
    if (parameters.isEmpty()) {
      System.out.println("Please provide an artifact to process");
      jc.usage();
      System.exit(0);
    }

    // Create Aether stuff
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, FileTransporterFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
    locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
      @Override
      public void serviceCreationFailed(Class<?> type, Class<?> impl, Throwable exception) {
        exception.printStackTrace();
      }
    });
    RepositorySystem system = locator.getService(RepositorySystem.class);
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
    String sep = System.getProperty("file.separator");;
    LocalRepository localRepo = new LocalRepository(System.getProperty("user.home") + sep + ".m2" + sep + "repository");
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
    session.setRepositoryListener(new AbstractRepositoryListener() {
      @Override
      public void artifactDownloading(RepositoryEvent event) {
        System.out.println("Downloading " + event.getArtifact());
      }
    });
    List<RemoteRepository> remotes = Arrays.asList(
        new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2/").build(),
        new RemoteRepository.Builder("snapshot", "default", "https://oss.sonatype.org/content/repositories/snapshots/").build()
    );

    // Process artifacts to build sources jars and classpath
    List<JarFile> jarFiles = new ArrayList<>();
    Set<File> classpath = new LinkedHashSet<>();
    for (String parameter : parameters) {

      DefaultArtifact resolvedArtifact = new DefaultArtifact(parameter);
      DefaultArtifact resolvedSourceArtifact = new DefaultArtifact(resolvedArtifact.getGroupId(), resolvedArtifact.getArtifactId(),
          "sources", resolvedArtifact.getExtension(), resolvedArtifact.getVersion());
      session.setDependencySelector(new RootDependencySelector(resolvedArtifact));

      CollectRequest collectRequest = new CollectRequest();
      collectRequest.setRoot(new Dependency(resolvedSourceArtifact, JavaScopes.COMPILE));
      collectRequest.setRepositories(remotes);

      DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE));

      List<ArtifactResult> artifactResults = system.resolveDependencies(session, dependencyRequest).getArtifactResults();

      Artifact result = artifactResults.stream().map(ArtifactResult::getArtifact).filter(art ->
              art.getGroupId().equals(resolvedArtifact.getGroupId()) && art.getGroupId().equals(resolvedArtifact.getGroupId()) && art.getClassifier().equals("sources")
      ).findFirst().get();
      File file = result.getFile();
      JarFile jarFile = new JarFile(file);
      jarFiles.add(jarFile);

      //
      artifactResults.stream().map(ArtifactResult::getArtifact).filter(art -> art.getClassifier().isEmpty()).map(Artifact::getFile).forEach(classpath::add);
    }


    // Build JavaFileObject from jars
    byte[] buffer = new byte[512];
    List<JavaFileObject> javaFiles = new ArrayList<>();
    for (JarFile jarFile : jarFiles) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry jarEntry = entries.nextElement();
        if (jarEntry.getName().endsWith(".java")) {
          ByteArrayOutputStream baos = new ByteArrayOutputStream((int)jarEntry.getSize());
          InputStream in = jarFile.getInputStream(jarEntry);
          while (true) {
            int len = in.read(buffer);
            if (len == -1) {
              break;
            }
            baos.write(buffer, 0, len);
          }
          String content = baos.toString();
          JavaFileObject javaFile = new SimpleJavaFileObject(URI.create("string://" + jarEntry.getName()), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
              return content;
            }
          };
          javaFiles.add(javaFile);
        }
      }
    }

    // Add the file to the classpath
    if (codegen != null) {
      File codegenFile = new File(codegen);
      if (!codegenFile.exists()) {
        throw new FileNotFoundException("Codegen file " + codegen + " does not exists");
      }
      if (!codegenFile.isFile()) {
        throw new Exception("Codegen file " + codegen + " must be a file");
      }
      URLClassLoader classLoader = (URLClassLoader) CodeGenProcessor.class.getClassLoader();
      Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
      addURL.setAccessible(true);
      addURL.invoke(classLoader, codegenFile.getParentFile().toURI().toURL());
    }

    // Builder options
    List<String> options = new ArrayList<>();
    options.add("-proc:only");
    if (target != null) {
      File targetFile = new File(target);
      if (!targetFile.exists()) {
        throw new FileNotFoundException("Target dir " + target + " does not exists");
      }
      if (!targetFile.isDirectory()) {
        throw new Exception("Target dir " + target + " must be a dir");
      }
      options.add("-AoutputDirectory=" + targetFile.getAbsolutePath());
    }


    // Now compile
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    StandardJavaFileManager fileMgr = compiler.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8);
    fileMgr.setLocation(StandardLocation.CLASS_PATH, classpath);
    JavaCompiler.CompilationTask task = compiler.getTask(new OutputStreamWriter(System.out), fileMgr, diagnostics, options, Collections.emptyList(), javaFiles);
    task.setProcessors(Collections.singletonList(new CodeGenProcessor()));

    if (!task.call()) {
      diagnostics.getDiagnostics().forEach(System.out::println);
    }
  }
}
