package com.jtmelton.tpl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.jtmelton.tpl.cli.Options;
import com.jtmelton.tpl.domain.ClassNode;
import com.jtmelton.tpl.domain.JarNode;
import com.jtmelton.tpl.report.IReporter;
import com.jtmelton.tpl.results.QueryResult;
import com.jtmelton.tpl.results.ResultsProcessor;
import com.jtmelton.tpl.utils.Filters;
import com.jtmelton.tpl.utils.JavassistUtil;
import com.jtmelton.tpl.utils.QueryUtil;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  private final Collection<IReporter> reporters = new ArrayList<>();

  private final int threads;

  private GraphDatabaseService graphDb;

  private final AtomicInteger classesProcessed = new AtomicInteger();

  private final AtomicInteger jarsProcessed = new AtomicInteger();

  private ExecutorService executor;

  private long startTime;

  public ThirdPartyLibraryAnalyzer(Options options) {
    this.jarsDirectory = options.getJarsDirectory();
    this.classesDirectory = options.getClassesDirectory();
    this.dbDirectory = options.getDbDirectory();
    this.threads = options.getThreads();
    this.executor = Executors.newFixedThreadPool(threads);
  }

  private void dbSetup() {
    GraphDatabaseFactory factory = new GraphDatabaseFactory();
    graphDb = factory.newEmbeddedDatabase(new File(dbDirectory));
    Runtime.getRuntime().addShutdownHook(new Thread(graphDb::shutdown));
  }

  public void buildDependencyGraph(Options options) throws IOException, InterruptedException {
    startTime = System.nanoTime();

    dbSetup();

    Predicate<Path> filter = o -> true;
    if(options.isExcludeTestDirs()) {
      filter = Filters.filterTestDirs();
    }

    Collection<Path> customClasses = findPathsByExt(new File(classesDirectory), ".class", filter);

    LOG.info("Built class file paths, will process {} paths", customClasses.size());
    customClassNames.addAll(JavassistUtil.collectClassFiles(customClasses));

    LOG.info("Analyzing {} class files", customClassNames.size());
    Collection<ClassNode> classNodes = JavassistUtil.analyzeClassFiles(customClasses, customClassNames, classToUsedClassesMap);

    jars.addAll(findPathsByExt(new File(jarsDirectory), ".jar", Filters.depExclude(options.getDepExclusions())));

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
      int processed = jarsProcessed.incrementAndGet();
      j.getClassNodes().forEach(c -> QueryUtil.writeClassToJarRel(graphDb, j, c));
      LOG.info("Written {} of {} jars and jar relationships to db",
              processed, jarNodes.size());
    }));

    // Wait for all current threads to complete then spin up new thread pool.
    // All class nodes must be written to DB before relationships can be written
    executor.shutdown();
    executor.awaitTermination(24, HOURS);
    executor = Executors.newFixedThreadPool(threads);

    classesProcessed.set(0);

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

  public void reportUnusedJars(Options options) throws InterruptedException {
    if(graphDb == null) {
      dbSetup();
    }

    List<Map<String, Object>> jars = QueryUtil.getAllJars(graphDb);

    List<Map<String, Object>> filteredJars = jars.stream()
            .filter(Filters.searchJarInclude(options.getSearchJarInclusions()))
            .filter(Filters.searchJarExclude(options.getSearchJarExclusions()))
            .collect(Collectors.toList());

    Collection<String> unusedJars = new ArrayList<>();

    AtomicInteger i = new AtomicInteger();
    i.set(0);

    Set<String> processed = new HashSet<>();

    for(Map<String, Object> entry : filteredJars) {
      String name = (String) entry.get("name");

      if(!processed.contains(name)) {
        processed.add(name);
        executor.submit(QueryUtil.searchIfJarIsUsed(graphDb, entry, unusedJars, i,
                filteredJars.size(), options.getSearchTimeout()));
      } else if(processed.contains(name)) {
        i.incrementAndGet();
      }
    }

    executor.shutdown();
    executor.awaitTermination(24, HOURS);

    LOG.info("Writing results");

    String outputDir = options.getOutputDir();

    File outputDirFile = new File(outputDir);
    outputDirFile.mkdirs();

    File output = Paths.get(outputDir, "unusedJars.txt").toFile();
    try(PrintWriter writer = new PrintWriter(output)) {
      unusedJars.forEach(writer::println);
    } catch(FileNotFoundException fnfe) {
      LOG.error("Failed to write results to file.", fnfe);
    }
  }

  public void reportDependencies(Collection<String> searchTerms, Options options) throws InterruptedException {
    startTime = System.nanoTime();

    if(graphDb == null) {
      dbSetup();
    }

    ResultsProcessor processor = new ResultsProcessor(graphDb);
    reporters.forEach(processor::registerReporter);

    for(String searchTerm : searchTerms) {
      LOG.info("Searching for dependencies of {}", searchTerm);

      QueryResult results = QueryUtil.findDependencies(graphDb, searchTerm, options);
      LOG.info("Processing results for {} import chains", results.getClassChains().size());
      processor.process(results);
    }

    processor.generateReports(options.getOutputDir());

    long elapsedTime = System.nanoTime() - startTime;
    LOG.info("Report generation time {}", formatElapsedTime(elapsedTime));
  }

  public void reportAffectedClasses(Collection<String> searchTerms, Options options) throws InterruptedException {
    if(graphDb == null) {
      dbSetup();
    }

    startTime = System.nanoTime();

    ResultsProcessor processor = new ResultsProcessor(graphDb);
    reporters.forEach(processor::registerReporter);

    for(String searchTerm : searchTerms) {
      LOG.info("Searching for classes affected by dependency {}", searchTerm);

      QueryResult results = QueryUtil.findAffectedUserClasses(graphDb, searchTerm, options);
      LOG.info("Processing results for {} import chains", results.getClassChains().size());
      processor.process(results);
    }

    processor.generateReports(options.getOutputDir());

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
      int processed = classesProcessed.incrementAndGet();
      LOG.info("Written {} of {} classes to db",
              processed, externalClassNodes.size());
    };
  }

  private Collection<Path> findPathsByExt(File dir, String ext, Predicate<Path> filter) throws IOException {
    Collection<Path> paths = Files.walk(Paths.get(dir.getAbsolutePath()))
            .filter(p -> p.toAbsolutePath().toFile().getAbsolutePath().endsWith(ext))
            .filter(filter)
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
