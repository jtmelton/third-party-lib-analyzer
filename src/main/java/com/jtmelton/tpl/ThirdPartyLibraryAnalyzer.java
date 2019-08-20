package com.jtmelton.tpl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.jtmelton.tpl.cli.Options;
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

  private Collection<IReporter> reporters = new ArrayList<>();

  private final int threads;

  private final boolean singleThreadSearch;

  private final int searchTimeout;

  private final Collection<String> depExclusions;

  private final Collection<String> searchJarExclusions;

  private final Collection<String> searchJarInclusions;

  private final boolean filterResults;

  private final boolean excludeTestDirs;

  private GraphDatabaseService graphDb;

  private final AtomicInteger classesProcessed = new AtomicInteger();

  private final AtomicInteger jarsProcessed = new AtomicInteger();

  private final AtomicInteger userClassCounter = new AtomicInteger();

  private final AtomicInteger jarSearchesCounter = new AtomicInteger();

  private ExecutorService executor;

  private long startTime;

  public ThirdPartyLibraryAnalyzer(Options options) {
    this.jarsDirectory = options.getJarsDirectory();
    this.classesDirectory = options.getClassesDirectory();
    this.dbDirectory = options.getDbDirectory();
    this.threads = options.getThreads();
    this.executor = Executors.newFixedThreadPool(threads);
    this.singleThreadSearch = options.isSingleThreadSearch();
    this.searchTimeout = options.getSearchTimeout();
    this.depExclusions = options.getDepExclusions();
    this.excludeTestDirs = options.isExcludeTestDirs();
    this.searchJarExclusions = options.getSearchJarExclusions();
    this.searchJarInclusions = options.getSearchJarInclusions();
    this.filterResults = options.isFilterResults();
  }

  private void dbSetup() {
    GraphDatabaseFactory factory = new GraphDatabaseFactory();
    graphDb = factory.newEmbeddedDatabase(new File(dbDirectory));
    Runtime.getRuntime().addShutdownHook(new Thread(graphDb::shutdown));
  }

  public void buildDependencyGraph() throws IOException, InterruptedException {
    startTime = System.nanoTime();

    dbSetup();

    Collection<Path> customClasses = findPathsByExt(new File(classesDirectory), ".class", filterTestDirs());

    LOG.info("Built class file paths, will process {} paths", customClasses.size());
    customClassNames.addAll(JavassistUtil.collectClassFiles(customClasses));

    LOG.info("Analyzing {} class files", customClassNames.size());
    Collection<ClassNode> classNodes = JavassistUtil.analyzeClassFiles(customClasses, customClassNames, classToUsedClassesMap);

    jars.addAll(findPathsByExt(new File(jarsDirectory), ".jar", depExclude()));

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

  public void reportUnusedJars(String outputDir) throws InterruptedException {
    if(graphDb == null) {
      dbSetup();
    }

    List<Map<String, Object>> jars = QueryUtil.getAllJars(graphDb);

    List<Map<String, Object>> filteredJars = jars.stream()
            .filter(searchJarInclude())
            .filter(searchJarExclude())
            .collect(Collectors.toList());

    Collection<String> unusedJars = new ArrayList<>();

    AtomicInteger i = new AtomicInteger();
    i.set(0);

    Set<String> processed = new HashSet<>();

    for(Map<String, Object> entry : filteredJars) {
      String name = (String) entry.get("name");

      if(!processed.contains(name)) {
        processed.add(name);
        executor.submit(searchIfJarIsUsed(entry, unusedJars, i, filteredJars.size()));
      } else if(processed.contains(name)) {
        i.incrementAndGet();
      }
    }

    executor.shutdown();
    executor.awaitTermination(24, HOURS);

    LOG.info("Writing results");

    File outputDirFile = new File(outputDir);
    outputDirFile.mkdirs();

    File output = Paths.get(outputDir, "unusedJars.txt").toFile();
    try(PrintWriter writer = new PrintWriter(output)) {
      unusedJars.forEach(writer::println);
    } catch(FileNotFoundException fnfe) {
      LOG.error("Failed to write results to file.", fnfe);
    }
  }

  public void reportAffectedClasses(Collection<String> searchTerms, String depth,
                                    String outputDir) throws InterruptedException {
    if(graphDb == null) {
      dbSetup();
    }

    startTime = System.nanoTime();

    ResultsProcessor processor = new ResultsProcessor(graphDb);
    reporters.forEach(processor::registerReporter);

    for(String searchTerm : searchTerms) {
      LOG.info("Searching for classes affected by dependency {}", searchTerm);

      QueryResult results = findAffectedUserClasses(searchTerm, depth, singleThreadSearch);
      LOG.info("Processing results for {} import chains", results.getClassChains().size());
      processor.process(results);
    }

    processor.generateReports(outputDir);

    long elapsedTime = System.nanoTime() - startTime;
    LOG.info("Report generation time {}", formatElapsedTime(elapsedTime));
  }

  private Runnable searchIfJarIsUsed(Map<String, Object> entry, Collection<String> results,
                                     AtomicInteger i, int size) {
    return () -> {
      String name = (String) entry.get("name");

      LOG.info("Jar {} of {}: {}", i.incrementAndGet(), size, name);

      List<Long> ids = QueryUtil.getJarClassIds((Long) entry.get("id"), graphDb);

      boolean result = false;
      for(long id : ids) {
        result = QueryUtil.isClassUsedByUser(id, graphDb, searchTimeout);

        if(result) {
          break;
        }
      }

      if(!result) {
        synchronized (results) {
          results.add(name);
        }
      }
    };
  }

  private QueryResult findAffectedUserClasses(String searchTerm, String depth,
                                             boolean singleThreadSearch) throws InterruptedException {
    QueryResult results = new QueryResult(searchTerm);
    ExecutorService executor;

    if (singleThreadSearch) {
      executor = Executors.newFixedThreadPool(1);
    } else {
      executor = Executors.newFixedThreadPool(2);
    }

    searchJarToClass(graphDb, results, depth, searchTerm, executor, searchTimeout);

    executor.shutdown();
    executor.awaitTermination(24, HOURS);

    userClassCounter.set(0);
    jarSearchesCounter.set(0);

    return results;
  }

  private void searchJarToClass(GraphDatabaseService graphDb, QueryResult results, String depth,
                                      String searchTerm, ExecutorService executor, int timeout) {
    List<Map<String, Object>> allJars = QueryUtil.getAllJars(graphDb);

    final List<Map<String, Object>> jars = allJars.stream()
            .filter(searchJarInclude())
            .filter(searchJarExclude())
            .filter(j -> ((String) j.get("name")).contains(searchTerm))
            .collect(Collectors.toList());

    LOG.info("Retrieved {} jar nodes", jars.size());

    if(jars.isEmpty()) {
      LOG.info("No jars found to analyze");
      return;
    }

    Set<String> uniqueJars = new HashSet<>();
    for(Map<String, Object> jar : jars) {
      String absoluteJarName = (String) jar.get("name");
      Path jarPath = Paths.get(absoluteJarName, "");
      String jarName = jarPath.getFileName().toString();

      // TODO: Switch to storing hashes of jars in DB for identifying duplicate
      if(uniqueJars.contains(jarName)) {
        jarSearchesCounter.incrementAndGet();
        results.addJarName(absoluteJarName);
        LOG.info("Ignoring duplicate jar {}", absoluteJarName);
        continue;
      }

      uniqueJars.add(jarName);

      jarSearchesCounter.incrementAndGet();
      executor.submit(QueryUtil.searchForUserClasses(graphDb, jar, results, depth,
              timeout, jars.size(), userClassCounter, filterResults));
    }
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

  private Predicate<Path> depExclude() {
    return p -> {
      for(String exclusion : depExclusions) {
        Pattern pattern = Pattern.compile(exclusion);
        Matcher matcher = pattern.matcher(p.toString());

        if(matcher.matches()) {
          return false;
        }
      }

      return true;
    };
  }

  private Predicate<Map<String, Object>> searchJarInclude() {
    return j -> {
      if(searchJarInclusions.isEmpty()) {
        return true;
      }

      String name = (String) j.get("name");
      for(String regex : searchJarInclusions) {
        if(name.matches(regex)) {
          return true;
        }
      }

      return false;
    };
  }

  private Predicate<Map<String, Object>> searchJarExclude() {
    return j -> {
      if(searchJarExclusions.isEmpty()) {
        return true;
      }

      String name = (String) j.get("name");
      for(String regex : searchJarExclusions) {
        if(name.matches(regex)) {
          return false;
        }
      }

      return true;
    };
  }

  private Predicate<Path> filterTestDirs() {
    return p -> {
      if(!excludeTestDirs) {
        return true;
      }

      String path = p.toAbsolutePath().toFile().getAbsolutePath();
      if(path.contains("/")) {
        return !path.contains("/test/");
      } else if(path.contains("\\\\")) {
        return !path.contains("\\\\test\\\\");
      }
      return true;
    };
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
