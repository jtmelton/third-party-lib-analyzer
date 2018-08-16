package com.jtmelton.tpl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.sampullara.cli.Args;
import com.sampullara.cli.Argument;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javassist.ClassPool;
import javassist.CtClass;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class ThirdPartyLibraryAnalyzer {

  private static final Logger logger = LoggerFactory.getLogger(ThirdPartyLibraryAnalyzer.class);

  private static final String NEWLINE = System.lineSeparator();

  @Argument(value = "jarsDirectory",
      required = true,
      description = "Directory containing 3rd/4th party library jars for analysis")
  private static String jarsDirectory = null;

  @Argument(value = "classesDirectory",
      required = true,
      description = "Directory containing java classes (class files) for analysis")
  private static String classesDirectory = null;

  private static final int BATCH_SIZE = 10000;

  // ******* set of custom class names *******
  private static Set<String> customClassNames = new HashSet<>();
  // ******* MAP OF {class -> jar class exists in} *******
  private static Map<String, String> classToJarNameMap = new HashMap<>();
  // ******* MAP OF {class -> classes referenced by this class} *******
  private static Map<String, Collection<String>> classToUsedClassesMap = new HashMap<>();
  private static GraphDatabaseService graphDb;

  public static void main(String[] args) throws Exception {
    long startMillis = System.currentTimeMillis();

    //TODO: delete me
    GraphDatabaseFactory graphDbFactory = new GraphDatabaseFactory();
//    GraphDatabaseService graphDb = graphDbFactory.newEmbeddedDatabase(new File("data/cars"));
    graphDb = graphDbFactory.newEmbeddedDatabase(new File("data/third-party-libraries"));
    registerShutdownHook(graphDb);
//    graphDb.beginTx();

//    Node car = graphDb.createNode(Label.label("Car"));
//    car.setProperty("make", "tesla");
//    car.setProperty("model", "model3");
//
//
//    Node owner = graphDb.createNode(Label.label("Person"));
//    owner.setProperty("firstName", "baeldung");
//    owner.setProperty("lastName", "baeldung");
//
//    owner.createRelationshipTo(car, RelationshipType.withName("owner"));

//    Result result = graphDb.execute(
//        "MATCH (c:Car) <-[owner]- (p:Person) " +
//        "WHERE c.make = 'tesla'" +
//        "RETURN p.firstName, p.lastName");
//
//    System.err.println(result);
//
//    result = graphDb.execute(
//        "CREATE (baeldung:Company {name:\"Baeldung\"}) " +
//        "-[:owns]-> (tesla:Car {make: 'tesla', model: 'modelX'})" +
//        "RETURN baeldung, tesla");
//
//    System.err.println(result);
//
//    result = graphDb.execute(
//        "MATCH (company:Company)-[:owns]-> (car:Car)" +
//        "WHERE car.make='tesla' and car.model='modelX'" +
//        "RETURN company.name");
//
//    System.err.println(result);
//
//    Map<String, Object> params = new HashMap<>();
//    params.put("name", "baeldung");
//    params.put("make", "tesla");
//    params.put("model", "modelS");
//
//    result = graphDb.execute("CREATE (baeldung:Company {name:$name}) " +
//                                    "-[:owns]-> (tesla:Car {make: $make, model: $model})" +
//                                    "RETURN baeldung, tesla", params);

//    System.err.println(result);

    //TODO: delete me

    ThirdPartyLibraryAnalyzer analyzer = new ThirdPartyLibraryAnalyzer();
    analyzer.parseArguments(args);

    Collection<Path> classFilePaths = analyzer.findPathsByExtension(new File(classesDirectory), ".class");
    logger.info("Built class file paths, will process {} paths", classFilePaths.size());
    analyzer.collectClassFiles(classFilePaths);
    logger.info("Completed collection, pulling results");

    logger.info("# of custom classes: {}", customClassNames.size());

    logger.info("attempting to analyze class files: {}", classFilePaths.size());
    analyzer.analyzeClassFiles(classFilePaths);
    logger.info("attempting to analyze jar files: {}", jarsDirectory);
    analyzer.analyzeJarFiles(new File(jarsDirectory));

    logger.info("# of overall classes: {}", classToJarNameMap.keySet().size());
    logger.info("# of mappings to used classes: {}", classToUsedClassesMap.size());

    Collection<String> uniqueJarNames = new HashSet<>(classToJarNameMap.values());

    // ******* MAP OF {jar -> list of jars this jar uses/references} *******
    Map<String, Collection<String>> jarDependentJarMap = new HashMap<>();

    // STEP 1: go from
    //      jar ->
    //      custom classes in jar ->
    //      classes referenced by custom classes in jar ->
    //      jars containing classes referenced by custom classes in jar
    // so we get {jar -> dependent jars} map where "dependent" means A (jar) uses/references B (dependent jar)

    // loop through each jar represented
    for (String uniqueJarName : uniqueJarNames) {

      Collection<String> dependentJars = new HashSet<>();

      // loop through each class (all)
      for (String className : classToJarNameMap.keySet()) {

        // if this class exists in the jar I'm looking at
        if (uniqueJarName.equals(classToJarNameMap.get(className))) {

          // find all used classes
          Collection<String> mappedClasses = classToUsedClassesMap.get(className);

          // loop through each mapped class
          for (String mappedClass : mappedClasses) {

            // add the mapped class's containing jar to the used jars for this particular jar
            dependentJars.add(classToJarNameMap.get(mappedClass));
          }
        }
      }

//      logger.info("jar {} uses the following jars: {}", uniqueJarName, dependentJars);
      jarDependentJarMap.put(uniqueJarName, dependentJars);
    }

//    logger.info("dependent jar map: {}", jarDependentJarMap);

    // STEP 2: go from
    //      dependent jar (from step 1) ->
    //      calling (used by) jar ->
    // so we get {dependent jar -> jars} map where "dependent" means A (dependent jar) is used by B (jar)

    // ******* MAP OF {dependent-jar -> list of jars this jar is used by) *******
    SetMultimap<String, String> dependentJarUsedByJarMap = MultimapBuilder.hashKeys().hashSetValues().build();

    for (String jar : jarDependentJarMap.keySet()) {
      Collection<String> dependentJars = jarDependentJarMap.get(jar);

      for (String dependentJar : dependentJars) {
        if (jar == null || dependentJar == null) {
          continue;
        }

        if (dependentJar.equals(jar)) {
          continue;
        }
        dependentJarUsedByJarMap.put(dependentJar, jar);
      }
    }

    // ******* MAP OF {jar -> list of classes in this jar) *******
    Multimap<String, String> jarNameToClassMap = HashMultimap.create();

    for (String className : classToJarNameMap.keySet()) {
      jarNameToClassMap.put(classToJarNameMap.get(className), className);
    }

    for (String analyzedJar : dependentJarUsedByJarMap.keySet()) {
      Collection<String> usedByJars = dependentJarUsedByJarMap.get(analyzedJar);
//      logger.info("jar {} used by the following jars: {}", analyzedJar, dependentJars);
      try {
        String msg = "jar " + analyzedJar + " used by the following jars: " + usedByJars + " \r\n";
        Files.write(Paths.get("/tmp/3pla.txt"), msg.getBytes(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
      } catch (IOException e) {
        //exception handling left as an exercise for the reader
      }

      Collection<String> affectedClasses = new HashSet<>();

      for (String customClassName : customClassNames) {
        Collection<String> referencedClasses = classToUsedClassesMap.get(customClassName);
        for (String referencedClass : referencedClasses) {
          String referencedClassJarName = classToJarNameMap.get(referencedClass);
          if (analyzedJar.equals(referencedClassJarName)) {
            try {
              String
                  msg =
                  "\t\t- custom class (" + customClassName + ") added b/c of analyzed jar: " + referencedClassJarName
                  + "  \r\n";
              Files.write(Paths.get("/tmp/3pla.txt"), msg.getBytes(), StandardOpenOption.APPEND,
                          StandardOpenOption.CREATE);
            } catch (IOException e) {
              //exception handling left as an exercise for the reader
            }
            affectedClasses.add(customClassName);
          }
          if (usedByJars.contains(referencedClassJarName)) {
            try {
              String
                  msg =
                  "\t\t- custom class (" + customClassName + ") added b/c of usedByJar jar: " + referencedClassJarName
                  + "  \r\n";
              Files.write(Paths.get("/tmp/3pla.txt"), msg.getBytes(), StandardOpenOption.APPEND,
                          StandardOpenOption.CREATE);
            } catch (IOException e) {
              //exception handling left as an exercise for the reader
            }
            affectedClasses.add(customClassName);
          }
        }
      }

      try {
        String msg = "\tbthese custom classes would be affected (" + affectedClasses.size() + "):  \r\n";
        Files.write(Paths.get("/tmp/3pla.txt"), msg.getBytes(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
      } catch (IOException e) {
        //exception handling left as an exercise for the reader
      }
//      logger.info("\tthese custom classes would be affected: {}", affectedClasses);
    }

    graphDb.shutdown();

    long endMillis = System.currentTimeMillis();
    logger.info("Execution completed in {}s", (endMillis - startMillis) / 1000);

  }

  private void parseArguments(String[] args) {
    Args.parse(this, args);
  }

  private void analyzeJarFiles(File jarsDir) throws IOException {

    Collection<Path> jars = findPathsByExtension(jarsDir, ".jar");
    logger.info("# jar files: {}", jars.size());

    int i = 0;
    for (Path jar : jars) {
      ClassPool classPool = new ClassPool();
      final JarFile artifactJarFile = new JarFile(jar.toFile());
      final Enumeration<JarEntry> jarEntries = artifactJarFile.entries();

      while (jarEntries.hasMoreElements()) {
        final JarEntry jarEntry = jarEntries.nextElement();

        if (jarEntry.getName().endsWith(".class")) {
          i++;
          if (i % BATCH_SIZE == 0) {
            logger.info("processing # {}", i);
            logmemory();

            classPool = null;
            classPool = new ClassPool();
          }

          InputStream is = null;
          try {
            is = artifactJarFile.getInputStream(jarEntry);
            CtClass ctClass = classPool.makeClass(is);
            final Collection<String> referencedClassNames = ctClass.getRefClasses();
//            System.err.println(ctClass.getName());
//            System.err.println("\t" + referencedClassNames);
            classToJarNameMap.put(ctClass.getName(), artifactJarFile.getName());

            try (Transaction tx = graphDb.beginTx()) {
              // TODO: DELETE ME
              Node classNode = getOrCreateNode("class", "name", ctClass.getName());
              Node jarNode = getOrCreateNode("jar", "path", artifactJarFile.getName());

              jarNode.createRelationshipTo(classNode, RelationshipType.withName("contains"));

              tx.success();
            }
            // TODO: END DELETE ME

            classToUsedClassesMap.put(ctClass.getName(), removeCustomClasses(referencedClassNames));


          } finally {
            try {
              if (is != null) {
                is.close();
              }
            } catch (IOException ignored) {
              // Ignore
            }
          }
        }
      }
    }
  }

  private Node getOrCreateNode(String labelName, String propertyName, String propertyValue) {
//    try (Transaction tx = graphDb.beginTx()) {
    Node classNode = graphDb.findNode(Label.label(labelName), propertyName, propertyValue);

    if (classNode != null) {
      return classNode;
    }

    classNode = graphDb.createNode(Label.label(labelName));
    classNode.setProperty(propertyName, propertyValue);

//      tx.success();

    return classNode;
//    }
  }

  private static void registerShutdownHook(final GraphDatabaseService graphDb) {
    // Registers a shutdown hook for the Neo4j instance so that it
    // shuts down nicely when the VM exits (even if you "Ctrl-C" the
    // running application).
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        graphDb.shutdown();
      }
    });
  }

  private Collection<Path> findPathsByExtension(File directory, String extension) throws IOException {
    Collection<Path> paths = Files.walk(Paths.get(directory.getAbsolutePath()))
        .filter(p -> p.toAbsolutePath().toFile().getAbsolutePath().endsWith(extension))
        .collect(Collectors.toList());

    return paths;
  }

  private void collectClassFiles(Collection<Path> paths) throws IOException {
    ClassPool classPool = new ClassPool();

    int i = 0;
    for (Path path : paths) {
      byte[] bFile = java.nio.file.Files.readAllBytes(path);

      i++;
      if (i % BATCH_SIZE == 0) {
        logger.info("processing # {}", i);
        logmemory();

        classPool = null;
        classPool = new ClassPool();
      }

      InputStream is = null;
      try {
        is = new ByteArrayInputStream(bFile);
        CtClass ctClass = classPool.makeClass(is);
        customClassNames.add(ctClass.getName());
      } finally {
        try {
          if (is != null) {
            is.close();
          }
        } catch (IOException ignored) {
          // Ignore
        }
      }
    }
  }

  private boolean classNameExists(String customClassname) {
    return customClassNames.contains(customClassname);
  }

  private void analyzeClassFiles(Collection<Path> paths) throws IOException {
    ClassPool classPool = new ClassPool();

    int i = 0;
    for (Path path : paths) {
      byte[] bFile = java.nio.file.Files.readAllBytes(path);

      i++;
      if (i % BATCH_SIZE == 0) {
        logger.info("processing # {}", i);
        logmemory();
        classPool = null;
        classPool = new ClassPool();
      }

      InputStream is = null;
      try {
        is = new ByteArrayInputStream(bFile);
        CtClass ctClass = classPool.makeClass(is);
        final Collection<String> referencedClassNames = ctClass.getRefClasses();
//        System.err.println(ctClass.getName());
//        System.err.println("\t" + referencedClassNames);
        classToJarNameMap.put(ctClass.getName(), "INTERNAL_CUSTOM_CODE");
        classToUsedClassesMap.put(ctClass.getName(), removeCustomClasses(referencedClassNames));
      } finally {
        try {
          if (is != null) {
            is.close();
          }
        } catch (IOException ignored) {
          // Ignore
        }
      }
    }
  }

  private Collection<String> removeCustomClasses(final Collection<String> referencedClasses) {
    final Collection<String> classes = new HashSet<>();

    for (String referencedClass : referencedClasses) {

      if (!classNameExists(referencedClass)) {
        classes.add(referencedClass);
      }
    }

    return classes;
  }

  private void logmemory() {
    logger.info("total/free/used MB: {} / {} / {}",
                (double) (Runtime.getRuntime().totalMemory()) / (1024 * 1024),
                (double) (Runtime.getRuntime().freeMemory()) / (1024 * 1024),
                (double) (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024));
    logger.info("custom/classtojar/classestoused : {} / {} / {}",
                customClassNames.size(),
                classToJarNameMap.size(),
                classToUsedClassesMap.size());
  }
}
