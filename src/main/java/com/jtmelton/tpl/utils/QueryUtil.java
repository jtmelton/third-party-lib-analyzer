package com.jtmelton.tpl.utils;

import com.jtmelton.tpl.cli.Options;
import com.jtmelton.tpl.results.QueryResult;
import com.jtmelton.tpl.domain.ClassNode;
import com.jtmelton.tpl.domain.JarNode;
import org.neo4j.graphdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class QueryUtil {

  private static final Logger LOG = LoggerFactory.getLogger(QueryUtil.class);

  private static final String CLASS_TO_OWNING_JAR_QUERY = "MATCH (class:Class) WHERE ID(class) = { `id` } WITH class " +
        "MATCH (class)-[:classes]-(jar:Jar) RETURN jar.name";

  private static final String WRITE_JAR_QUERY = "CREATE (n:Jar { name: { `name` } }) RETURN ID(n)";

  private static final String WRITE_CLASS_QUERY = "CREATE (n:Class { name: { `name` }, custom: { `custom` } }) RETURN ID(n)";

  private static final String WRITE_USER_CLASS_QUERY = "CREATE (n:UserClass { name: { `name` }, custom: { `custom` } }) RETURN ID(n)";

  private static final String CLASS_JAR_RELATIONSHIP_QUERY = "MATCH (jar:Jar), (class:Class) WHERE ID(jar) = { `jarID` } " +
          "AND ID(class) = { `classID` } CREATE (jar)<-[rel:classes]-(class)";

  private static final String USER_CLASS_DEPCLASS_RELATIONSHIP_QUERY = "MATCH (class:UserClass), (dep:Class) " +
          "WHERE ID(class) = { `classID` } AND ID(dep) = { `depID` } CREATE (class)-[rel:classesDependedOn]->(dep)";

  private static final String CLASS_DEPCLASS_RELATIONSHIP_QUERY = "MATCH (class:Class), (dep:Class) " +
          "WHERE ID(class) = { `classID` } AND ID(dep) = { `depID` } CREATE (class)-[rel:classesDependedOn]->(dep)";

  private static final String GET_ALL_JARS_QUERY = "MATCH (jar:Jar) RETURN collect({id: ID(jar), name: jar.name})";

  private static final String GET_ALL_USER_CLASSES_QUERY = "MATCH (class:UserClass) RETURN collect({id: ID(class), name: class.name})";

  private static final String JAR_TO_USER_CLASS_QUERY = "MATCH (jar:Jar) WHERE ID(jar) = { `id` } WITH jar " +
          "MATCH path = ((jar)<-[:classes]-(:Class)<-[:classesDependedOn*..%s]-(:UserClass)) " +
          "RETURN collect(extract(n IN nodes(path) | {id: ID(n), name: n.name}))";

  private static final String JAR_CLASS_QUERY = "MATCH (jar:Jar) WHERE ID(jar) = { `id` } WITH jar " +
          "MATCH (jar)<-[:classes]-(jarClass:Class) RETURN collect({id: ID(jarClass)})";

  private static final String CLASS_TO_USER_QUERY = "MATCH (jarClass:Class) WHERE ID(jarClass) = { `id` } WITH jarClass " +
          "MATCH (jarClass)<-[:classesDependedOn*..12]-(userClass:UserClass) RETURN userClass LIMIT 1";

  private static final String USER_CLASS_TO_JAR_QUERY = "MATCH (class:UserClass) WHERE ID(class) = { `id` } WITH class " +
          "MATCH path = ((class)-[:classesDependedOn*..%s]->(:Class)-[:classes]->(:Jar)) RETURN collect(extract(n IN nodes(path) | {id: ID(n), name: n.name}))";

  private static final AtomicInteger userClassCounter = new AtomicInteger();

  private static final AtomicInteger jarSearchesCounter = new AtomicInteger();

  public static QueryResult findAffectedUserClasses(GraphDatabaseService graphDb, String searchTerm, Options options) throws InterruptedException {
    QueryResult results = new QueryResult(searchTerm);
    ExecutorService executor;

    if (options.isSingleThreadSearch()) {
      executor = Executors.newFixedThreadPool(1);
    } else {
      executor = Executors.newFixedThreadPool(2);
    }

    searchJarToClass(graphDb, results, searchTerm, executor, options);

    executor.shutdown();
    executor.awaitTermination(24, HOURS);

    userClassCounter.set(0);
    jarSearchesCounter.set(0);

    return results;
  }

  public static List<Long> getJarClassIds(long id, GraphDatabaseService graphDb) {
    List<Long> classIds = new ArrayList<>();

    try(Transaction tx = graphDb.beginTx()) {
      Map<String, Object> params = new HashMap<>();
      params.put("id", id);

      Result query = graphDb.execute(JAR_CLASS_QUERY, params);
      while (query.hasNext()) {
        Map<String, Object> results = query.next();

        String key = "collect({id: ID(jarClass)})";
        List<Map<Object, Object>> classes = (List<Map<Object, Object>>) results.get(key);
        for(Map<Object, Object> clazz : classes) {
          long classId = (long) clazz.get("id");
          classIds.add(classId);
        }
      }

      tx.success();
    }

    return classIds;
  }

  public static boolean isClassUsedByUser(long id, GraphDatabaseService graphDb, long timeout) {
    boolean result;
    try(Transaction tx = graphDb.beginTx(timeout, MINUTES)) {
      Map<String, Object> params = new HashMap<>();
      params.put("id", id);

      Result query = graphDb.execute(CLASS_TO_USER_QUERY, params);
      result = query.hasNext();

      tx.success();
    }

    return result;
  }

  public static QueryResult findDependencies(GraphDatabaseService graphDb,
                                             String searchTerm,
                                             Options options) throws InterruptedException {
    QueryResult results = new QueryResult(searchTerm);
    ExecutorService executor;

    if (options.isSingleThreadSearch()) {
      executor = Executors.newFixedThreadPool(1);
    } else {
      executor = Executors.newFixedThreadPool(2);
    }

    searchUserClassToJar(graphDb, results, searchTerm, executor, options);

    executor.shutdown();
    executor.awaitTermination(24, HOURS);

    return results;
  }

  public static void searchUserClassToJar(GraphDatabaseService graphDb, QueryResult results,
                                          String searchTerm, ExecutorService executor, Options options) {
    List<Map<String, Object>> allClasses = getAllClasses(graphDb);

    final List<Map<String, Object>> classes = new ArrayList<>();

    Predicate<Map<String, Object>> filter = options.isExactMatch() ? Filters.equals(searchTerm) : Filters.contains(searchTerm);
    classes.addAll(allClasses.stream().filter(filter)
            .collect(Collectors.toList()));

    LOG.info("Retrieved {} user class nodes", classes.size());

    if(classes.isEmpty()) {
      return;
    }

    for(Map<String, Object> clazz : classes) {
      executor.submit(searchForDependencyJars(graphDb, clazz, results, options));
    }
  }

  private static List<Map<String, Object>> getAllClasses(GraphDatabaseService graphDb) {
    List<Map<String, Object>> allClasses = new ArrayList<>();

    try(Transaction tx = graphDb.beginTx()) {

      Result query = graphDb.execute(GET_ALL_USER_CLASSES_QUERY, Collections.EMPTY_MAP);

      while(query.hasNext()) {
        Map<String, Object> results = query.next();

        String key = "collect({id: ID(class), name: class.name})";
        allClasses = (List<Map<String, Object>>) results.get(key);
      }

      tx.success();
    }

    return allClasses;
  }

  public static List<Map<String, Object>> getAllJars(GraphDatabaseService graphDb) {
    List<Map<String, Object>> allJars = new ArrayList<>();

    try(Transaction tx = graphDb.beginTx()) {

      Result query = graphDb.execute(GET_ALL_JARS_QUERY, Collections.EMPTY_MAP);

      while (query.hasNext()) {
        Map<String, Object> results = query.next();

        String key = "collect({id: ID(jar), name: jar.name})";
        allJars = (List<Map<String, Object>>) results.get(key);
      }

      tx.success();
    }

    return allJars;
  }

  private static void searchJarToClass(GraphDatabaseService graphDb, QueryResult results,
                                String searchTerm, ExecutorService executor, Options options) {
    List<Map<String, Object>> allJars = QueryUtil.getAllJars(graphDb);

    Predicate<Map<String, Object>> filter = options.isExactMatch() ? Filters.equals(searchTerm) : Filters.contains(searchTerm);

    final List<Map<String, Object>> jars = allJars.stream()
            .filter(Filters.searchJarInclude(options.getSearchJarInclusions()))
            .filter(Filters.searchJarExclude(options.getSearchJarExclusions()))
            .filter(filter)
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
      executor.submit(QueryUtil.searchForUserClasses(graphDb, jar, results,
              jars.size(), userClassCounter, options));
    }
  }

  private static Callable<Boolean> searchForDependencyJars(GraphDatabaseService graphDb, Map<String, Object> clazz,
                                                           QueryResult results, Options options) {
    return () -> {
      boolean success = true;
      // TODO: Add dynamically decreasing depth

      try(Transaction tx = graphDb.beginTx(options.getSearchTimeout(), MINUTES)) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", clazz.get("id"));

        String query = String.format(USER_CLASS_TO_JAR_QUERY, options.getSearchDepth());
        Result result = graphDb.execute(query, params);

        while(result != null && result.hasNext()) {
          Map<String, Object> resultMap = result.next();

          String key = "collect(extract(n IN nodes(path) | {id: ID(n), name: n.name}))";
          List<List<Map<String, Object>>> chains = (List<List<Map<String, Object>>>) resultMap.get(key);

          if(chains.isEmpty()) {
            continue;
          }

          List<List<Map<String, Object>>> chainsToAdd;

          if(options.isFilterResults()) {
            chainsToAdd = filterChainResults(chains);
          } else {
            chainsToAdd = chains;
          }

          result = null;

          synchronized (results) {
            for(List<Map<String, Object>> chain : chainsToAdd) {
              results.addJarName((String) chain.get(chain.size() - 1).get("name"));
            }

            Set<String> added = new HashSet<>();
            // results.addClassChain expects jars to be first element
            for(List<Map<String, Object>> chain : chainsToAdd) {
              Collections.reverse(chain);

              if(added.contains((String) chain.get(1).get("name"))) {
                continue;
              }

              added.add((String) chain.get(1).get("name"));
              results.addClassChain(chain);
            }
          }
        }

        tx.success();
      } catch(TransactionTerminatedException tte) {
        LOG.warn("Search failed for {}", clazz.get("name"));
        success = false;
      }

      return success;
    };
  }

  public static Runnable searchIfJarIsUsed(GraphDatabaseService graphDb, Map<String, Object> entry, Collection<String> results,
                                     AtomicInteger i, int size, int searchTimeout) {
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

  public static Callable<Boolean> searchForUserClasses(GraphDatabaseService graphDb, Map<String, Object> jar,
                                                        QueryResult results, int totalJars,
                                                       AtomicInteger classCounter, Options options) {
    return () -> {
      boolean success = false;
      String depthToUse = options.getSearchDepth();

      while(!success) {
        try (Transaction tx = graphDb.beginTx(options.getSearchTimeout(), MINUTES)) {
          Map<String, Object> params = new HashMap<>();
          params.put("id", jar.get("id"));

          String query = String.format(JAR_TO_USER_CLASS_QUERY, depthToUse);
          Result result = graphDb.execute(query, params);

          while (result != null && result.hasNext()) {
            Map<String, Object> resultMap = result.next();

            String key = "collect(extract(n IN nodes(path) | {id: ID(n), name: n.name}))";
            List<List<Map<String, Object>>> chains = (List<List<Map<String, Object>>>) resultMap.get(key);

            if (chains.isEmpty()) {
              continue;
            }

            List<List<Map<String, Object>>> chainsToAdd;

            if(options.isFilterResults()) {
              chainsToAdd = filterChainResults(chains);
            } else {
              chainsToAdd = chains;
            }

            result = null;

            synchronized (results) {
              results.addJarName((String) jar.get("name"));
              chainsToAdd.forEach(results::addClassChain);
            }
          }

          int counter = classCounter.incrementAndGet();
          LOG.info("Completed {} out of {} search queries", counter, totalJars);
          tx.success();
        } catch (TransactionTerminatedException tte) {
          if(depthToUse.equals("1")) {
            LOG.warn("Search failed for {}", jar.get("name"));
            break;
          }

          int newDepth = Integer.parseInt(depthToUse) - 1;
          depthToUse = "" + newDepth;
          LOG.warn("Search at depth {} timed out, lowering depth to {} for {}",
                  Integer.parseInt(depthToUse) + 1, depthToUse, jar.get("name"));

          continue;
        }

        success = true;
      }

      return success;
    };
  }

  private static List<List<Map<String, Object>>> filterChainResults(List<List<Map<String, Object>>> chains) {
    Set<String> matchedUserClasses = new HashSet<>();

    List<List<Map<String, Object>>> filteredChains = chains.stream().filter(c -> {
      String jarName = (String) c.get(c.size() - 1).get("name");
      if (matchedUserClasses.contains(jarName)) {
        return false;
      } else {
        matchedUserClasses.add(jarName);
        return true;
      }
    }).collect(Collectors.toList());

    return filteredChains;
  }

  public static Collection<String> findOwningJarNames(GraphDatabaseService graphDb, long id) {
    Set<String> jarNames = new HashSet<>();

    try(Transaction tx = graphDb.beginTx()) {
      Map<String, Object> params = new HashMap<>();
      params.put("id", id);

      Result result = graphDb.execute(CLASS_TO_OWNING_JAR_QUERY, params);

      while(result.hasNext()) {
        String jarName = (String) result.next().get("jar.name");
        jarNames.add(jarName);
      }

      tx.success();
    } catch (QueryExecutionException qee) {
      LOG.warn("Class to owning jars query failed", qee);
    }

    return jarNames;
  }

  public static void writeJarNode(GraphDatabaseService graphDb, JarNode jarNode) {
    try(Transaction tx = graphDb.beginTx()) {
      Map<String, Object> params = new HashMap<>();
      params.put("name", jarNode.getName());

      Result result = graphDb.execute(WRITE_JAR_QUERY, params);

      while(result.hasNext()) {
        long id = (Long) result.next().get("ID(n)");
        jarNode.setId(id);
      }
      tx.success();
    } catch (QueryExecutionException qee) {
      LOG.warn("Jar creation query failed", qee);
    }
  }

  public static void writeClassNode(GraphDatabaseService graphDb, ClassNode classNode) {
    try(Transaction tx = graphDb.beginTx()) {
      Map<String, Object> params = new HashMap<>();
      params.put("name", classNode.getName());
      params.put("custom", classNode.isCustom());

      Result result;
      if(classNode.isCustom()) {
        result = graphDb.execute(WRITE_USER_CLASS_QUERY, params);
      } else {
        result = graphDb.execute(WRITE_CLASS_QUERY, params);
      }
      while(result.hasNext()) {
        long id = (Long) result.next().get("ID(n)");
        classNode.setId(id);
      }
      tx.success();
    } catch (QueryExecutionException qee) {
      LOG.warn("Class creation query failed", qee);
    }
  }

  public static void writeClassToJarRel(GraphDatabaseService graphDb, JarNode jarNode, ClassNode classNode) {
    try(Transaction tx = graphDb.beginTx()) {
      Map<String, Object> params = new HashMap<>();
      params.put("jarID", jarNode.getId());
      params.put("classID", classNode.getId());

      graphDb.execute(CLASS_JAR_RELATIONSHIP_QUERY, params);
      tx.success();
    } catch (QueryExecutionException qee) {
      LOG.warn("Class to owning jar relationship query failed", qee);
    }
  }

  public static void writeClassToClassRel(GraphDatabaseService graphDb, ClassNode classNode, ClassNode dependency) {
    try(Transaction tx = graphDb.beginTx()) {
      Map<String, Object> params = new HashMap<>();
      params.put("classID", classNode.getId());
      params.put("depID", dependency.getId());

      if(classNode.isCustom()) {
        graphDb.execute(USER_CLASS_DEPCLASS_RELATIONSHIP_QUERY, params);
      } else {
        graphDb.execute(CLASS_DEPCLASS_RELATIONSHIP_QUERY, params);
      }
      tx.success();
    } catch (QueryExecutionException qee) {
      LOG.warn("Class to dependency class relationship query failed", qee);
    }
  }
}
