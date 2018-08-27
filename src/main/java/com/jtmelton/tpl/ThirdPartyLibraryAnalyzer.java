package com.jtmelton.tpl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.jtmelton.tpl.domain.ClassNode;
import com.jtmelton.tpl.domain.JarNode;
import com.jtmelton.tpl.report.IReporter;
import com.jtmelton.tpl.report.JsonReporter;
import com.jtmelton.tpl.utils.JavassistUtil;
import com.jtmelton.tpl.utils.QueryUtil;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.ogm.drivers.embedded.driver.EmbeddedDriver;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class ThirdPartyLibraryAnalyzer {

  private static final Logger LOG = LoggerFactory.getLogger(ThirdPartyLibraryAnalyzer.class);

  private final String DOMAIN_PACKAGE = "com.jtmelton.tpl.domain";

  private final String jarsDirectory;

  private final String classesDirectory;

  private final String dbDirectory;

  // ******* set of custom class names *******
  private final Set<String> customClassNames = new HashSet<>();

  // ******* set of external class names *******
  private final Map<String, ClassNode> externalClassNodes = new HashMap<>();

  // ******* MAP OF {class -> classes referenced by this class} *******
  private final Multimap<String, String> classToUsedClassesMap = HashMultimap.create();

  private Session session;

  public ThirdPartyLibraryAnalyzer(String jarsDirectory, String classesDirectory,
                                   String dbDirectory) {
    this.jarsDirectory = jarsDirectory;
    this.classesDirectory = classesDirectory;
    this.dbDirectory = dbDirectory;
  }

  public void buildDependencyGraph() throws IOException {
    dbSetup();

    long startTime = System.nanoTime();

    Collection<Path> customClasses = findPathsByExt(new File(classesDirectory), ".class");

    LOG.info("Built class file paths, will process {} paths", customClasses.size());
    customClassNames.addAll(JavassistUtil.collectClassFiles(customClasses));

    LOG.info("# of custom classes: {}", customClassNames.size());
    LOG.info("Analyzing class files: {}", customClasses.size());
    Collection<ClassNode> classNodes = JavassistUtil.analyzeClassFiles(customClasses, customClassNames, classToUsedClassesMap);

    Collection<Path> jars = findPathsByExt(new File(jarsDirectory), ".jar");

    LOG.info("# jar files: {}", jars.size());
    Collection<JarNode> jarNodes = JavassistUtil.analyzeJarFiles(jars, externalClassNodes, classToUsedClassesMap);
    LOG.info("# external classes files: {}", externalClassNodes.size());

    buildDependencyRelationship(classNodes);
    buildDependencyRelationship(externalClassNodes.values());
    jarNodes.forEach(this::writeToDb);

    long elapsedTime = System.nanoTime() - startTime;

    LOG.info("DB Construction time {}", formatElapsedTime(elapsedTime));
    LOG.info("Created relationships for {} user classes, {} external classes, " +
            "and {} jars", customClassNames.size(), externalClassNodes.size(), jars.size());
  }

  public void reportAffectedClasses(Collection<String> searchTerms,
                                    String depth, String outputFile) throws IOException {
    if(session == null) {
      dbSetup();
    }

    String resultKey = "nodes(path)";

    IReporter reporter = new JsonReporter(session, resultKey);

    for(String searchTerm : searchTerms) {
      LOG.info("Searching for classes affected by dependency {}", searchTerm);

      Result result = QueryUtil.findAffectedUserClasses(session, searchTerm, depth);
      Collection<JarNode> matchedJars = QueryUtil.findMatchingJars(session, searchTerm);

      Collection<String> jarNames = new ArrayList<>();
      matchedJars.forEach(j -> jarNames.add(j.getName()));

      reporter.processResult(result, jarNames);
    }

    Path outputPath = Paths.get(outputFile);

    LOG.info("Writing JSON results: {}", outputPath.toAbsolutePath());
    Files.write(outputPath, reporter.getReport().getBytes());
  }

  private void buildDependencyRelationship(Collection<ClassNode> classNodes) {
    for(ClassNode classNode : classNodes) {
      for(String className : classToUsedClassesMap.get(classNode.getName())) {
        if(className.equals(classNode.getName()) || !externalClassNodes.containsKey(className)) {
          continue;
        }

        classNode.addDependedClass(externalClassNodes.get(className));
      }

      writeToDb(classNode);
    }
  }

  private void dbSetup() {
    GraphDatabaseFactory factory = new GraphDatabaseFactory();
    GraphDatabaseService graphDb = factory.newEmbeddedDatabase(new File(dbDirectory));
    EmbeddedDriver driver = new EmbeddedDriver(graphDb);

    SessionFactory sessionFactory = new SessionFactory(driver, DOMAIN_PACKAGE);
    session = sessionFactory.openSession();

    //Closes factory -> driver -> db
    Runtime.getRuntime().addShutdownHook(new Thread(sessionFactory::close));
  }

  private void writeToDb(Object obj) {
    try(Transaction tx = session.beginTransaction()) {
      session.save(obj, 1);
      tx.commit();
    }
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
}
