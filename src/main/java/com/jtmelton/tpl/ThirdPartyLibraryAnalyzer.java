package com.jtmelton.tpl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.jtmelton.tpl.domain.ClassNode;
import com.jtmelton.tpl.domain.JarNode;
import com.jtmelton.tpl.report.IReporter;
import com.jtmelton.tpl.results.QueryResult;
import com.jtmelton.tpl.results.ResultsProcessor;
import com.jtmelton.tpl.utils.JavassistUtil;
import com.jtmelton.tpl.utils.QueryUtil;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class ThirdPartyLibraryAnalyzer {

  private static final Logger LOG = LoggerFactory.getLogger(ThirdPartyLibraryAnalyzer.class);

  private final String jarsDirectory;

  private final String classesDirectory;

  private final String dbDirectory;

  // ******* set of custom class names *******
  private final Set<String> customClassNames = new HashSet<>();

  // ******* set of external class names *******
  private final Map<String, ClassNode> externalClassNodes = new HashMap<>();

  // ******* MAP OF {class -> classes referenced by this class} *******
  private final Multimap<String, String> classToUsedClassesMap = HashMultimap.create();

  private final Collection<Path> jars = new ArrayList<>();

  private Collection<IReporter> reporters = new ArrayList<>();

  private final int threads;

  private final boolean singleThreadSearch;

  private GraphDatabaseService graphDb;

  private AtomicInteger classesProcessed = new AtomicInteger();

  private AtomicInteger jarsProcessed = new AtomicInteger();

  private ExecutorService executor;

  private long startTime;

  public ThirdPartyLibraryAnalyzer(String jarsDirectory, String classesDirectory,
                                   String dbDirectory, int threads, boolean singleThreadSearch) {
    this.jarsDirectory = jarsDirectory;
    this.classesDirectory = classesDirectory;
    this.dbDirectory = dbDirectory;
    this.executor = Executors.newFixedThreadPool(threads);
    this.threads = threads;
    this.singleThreadSearch = singleThreadSearch;
  }

  private void dbSetup() {
    GraphDatabaseFactory factory = new GraphDatabaseFactory();
    graphDb = factory.newEmbeddedDatabase(new File(dbDirectory));
    Runtime.getRuntime().addShutdownHook(new Thread(graphDb::shutdown));
  }

  public void buildDependencyGraph() throws IOException, InterruptedException {
    startTime = System.nanoTime();

    dbSetup();

    Collection<Path> customClasses = findPathsByExt(new File(classesDirectory), ".class");

    LOG.info("Built class file paths, will process {} paths", customClasses.size());
    customClassNames.addAll(JavassistUtil.collectClassFiles(customClasses));

    LOG.info("Analyzing {} class files", customClassNames.size());
    Collection<ClassNode> classNodes = JavassistUtil.analyzeClassFiles(customClasses, customClassNames, classToUsedClassesMap);

    jars.addAll(findPathsByExt(new File(jarsDirectory), ".jar"));

    LOG.info("Analyzing {} jar files", jars.size());
    Collection<JarNode> jarNodes = JavassistUtil.analyzeJarFiles(jars, externalClassNodes, classToUsedClassesMap);
    LOG.info("# of external class files found: {}", externalClassNodes.size());

    LOG.info("Writing user classes to db");
    classNodes.forEach(c -> executor.submit(writeClassNode(c)));

    LOG.info("Writing external classes to db");
    externalClassNodes.values().forEach(c -> executor.submit(writeClassNode(c)));

    LOG.info("Writing jars and jar class relationships to db");
    jarNodes.forEach(j -> executor.submit(() -> {
      QueryUtil.writeJarNode(graphDb, j);
      jarsProcessed.incrementAndGet();
      j.getClassNodes().forEach(c -> QueryUtil.writeClassToJarRel(graphDb, j, c));
      LOG.info("Written {} of {} jars and jar relationships to db",
              jarsProcessed.get(), jarNodes.size());
    }));

    // Wait for all current threads to complete then spin up new thread pool.
    // All class nodes must be written to DB before relationships can be written
    executor.shutdown();
    executor.awaitTermination(24, HOURS);
    executor = Executors.newFixedThreadPool(threads);

    classesProcessed = new AtomicInteger();

    LOG.info("Writing user class dependency relationships to db");
    classNodes.forEach(c -> executor.submit(buildClassRelationships(c)));

    LOG.info("Writing external class dependency relationships to db");
    externalClassNodes.values().forEach(c -> executor.submit(buildClassRelationships(c)));

    executor.shutdown();
    executor.awaitTermination(24, HOURS);

    long elapsedTime = System.nanoTime() - startTime;
    LOG.info("DB Construction time {}", formatElapsedTime(elapsedTime));
    LOG.info("Created relationships for {} user classes, {} external classes, " +
            "and {} jars", customClassNames.size(), externalClassNodes.size(), jars.size());
  }

  public void reportAffectedClasses(Collection<String> searchTerms, String depth,
                                    String outputFile) throws InterruptedException {
    startTime = System.nanoTime();

    if(graphDb == null) {
      dbSetup();
    }

    ResultsProcessor processor = new ResultsProcessor(graphDb);
    reporters.forEach(processor::registerReporter);

    for(String searchTerm : searchTerms) {
      LOG.info("Searching for classes affected by dependency {}", searchTerm);

      QueryResult results = QueryUtil.findAffectedUserClasses(graphDb, searchTerm, depth, singleThreadSearch);
      LOG.info("Processing results for {} import chains", results.getClassChains().size());
      processor.process(results);
    }

    processor.generateReports(outputFile);

    long elapsedTime = System.nanoTime() - startTime;
    LOG.info("Report generation time {}", formatElapsedTime(elapsedTime));
  }

  private Runnable buildClassRelationships(ClassNode classNode) {
    return () -> {
      for (String className : classToUsedClassesMap.get(classNode.getName())) {
        if (className.equals(classNode.getName()) || !externalClassNodes.containsKey(className)) {
          continue;
        }

        ClassNode externalClass = externalClassNodes.get(className);
        QueryUtil.writeClassToClassRel(graphDb, classNode, externalClass);
      }

      int processed = classesProcessed.incrementAndGet();
      LOG.info("Written {} out of {} class dependency relationships",
              processed, externalClassNodes.size() + customClassNames.size());
    };
  }

  private Runnable writeClassNode(ClassNode classNode) {
    return () -> {
      QueryUtil.writeClassNode(graphDb, classNode);
      classesProcessed.incrementAndGet();
      LOG.info("Written {} of {} classes to db",
              classesProcessed.get(), externalClassNodes.size());
    };
  }

  private Collection<Path> findPathsByExt(File dir, String ext) throws IOException {
    Collection<Path> paths = Files.walk(Paths.get(dir.getAbsolutePath()))
            .filter(p -> p.toAbsolutePath().toFile().getAbsolutePath().endsWith(ext))
            .collect(Collectors.toList());

    return paths;
  }

  private String formatElapsedTime(long nanoTime) {
    long hs = NANOSECONDS.toHours(nanoTime);
    long min = NANOSECONDS.toMinutes(nanoTime) - HOURS.toMinutes(NANOSECONDS.toHours(nanoTime));
    long sec = NANOSECONDS.toSeconds(nanoTime) - MINUTES.toSeconds(NANOSECONDS.toMinutes(nanoTime));

    return String.format("%02d hs, %02d min, %02d sec", hs, min, sec);
  }

  public void registerReporter(IReporter reporter) {
    reporters.add(reporter);
  }
}
