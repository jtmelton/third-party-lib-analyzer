package com.jtmelton.tpl.utils;

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

  private static final String JAR_TO_USER_CLASS_QUERY = "MATCH (jar:Jar) WHERE ID(jar) = { `id` } WITH jar " +
          "MATCH path = ((jar)<-[:classes]-(:Class)<-[:classesDependedOn*..%s]-(:UserClass)) " +
          "RETURN collect(extract(n IN nodes(path) | {id: ID(n), name: n.name}))";

  private static final String JAR_CLASS_QUERY = "MATCH (jar:Jar) WHERE ID(jar) = { `id` } WITH jar " +
          "MATCH (jar)<-[:classes]-(jarClass:Class) RETURN collect({id: ID(jarClass)})";

  private static final String CLASS_TO_USER_QUERY = "MATCH (jarClass:Class) WHERE ID(jarClass) = { `id` } WITH jarClass " +
          "MATCH (jarClass)<-[:classesDependedOn*..12]-(userClass:UserClass) RETURN userClass LIMIT 1";

  private static final AtomicInteger userClassCounter = new AtomicInteger();

  private static final AtomicInteger jarSearchesCounter = new AtomicInteger();

  public static void searchJarToClass(GraphDatabaseService graphDb, QueryResult results, String depth,
                                      String searchTerm, ExecutorService executor, int timeout) {
    List<Map<String, Object>> allJars = getAllJars(graphDb);

    final List<Map<String, Object>> jars = new ArrayList<>();
    jars.addAll(allJars.stream().filter(j -> ((String) j.get("name")).contains(searchTerm))
            .collect(Collectors.toList()));

    LOG.info("Retrieved {} jar nodes", jars.size());

    if(!jars.isEmpty()) {
      Set<String> uniqueJars = new HashSet<>();
      for(Map<String, Object> jar : jars) {
        String absoluteJarName = (String) jar.get("name");
        Path jarPath = Paths.get(absoluteJarName, "");
        String jarName = jarPath.getFileName().toString();

        // TODO: Switch to storing hashes of jars in DB for identifying duplicate
        if(uniqueJars.contains(jarName)) {
          results.addJarName(absoluteJarName);
          LOG.info("Ignoring duplicate jar {}", absoluteJarName);
          continue;
        }

        uniqueJars.add(jarName);

        jarSearchesCounter.incrementAndGet();
        executor.submit(searchForUserClasses(graphDb, jar, results, depth, timeout));
      }
    }
  }

  public static QueryResult findAffectedUserClasses(GraphDatabaseService graphDb,
                                                    String searchTerm,
                                                    String depth,
                                                    boolean singleThreadSearch,
                                                    int searchTimeout) throws InterruptedException {
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

  private static Callable<Boolean> searchForUserClasses(GraphDatabaseService graphDb, Map<String, Object> jar,
                                                        QueryResult results, String depth, int timeout) {
    return () -> {
      boolean success = false;
      String depthToUse = depth;

      while(!success) {
        try (Transaction tx = graphDb.beginTx(timeout, MINUTES)) {
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

            List<List<Map<String, Object>>> filteredChains = filterChainResults(chains);
            result = null;

            synchronized (results) {
              results.addJarName((String) jar.get("name"));
              filteredChains.forEach(results::addClassChain);
            }
          }

          int counter = userClassCounter.incrementAndGet();
          LOG.info("Completed {} out of {} search queries", counter, jarSearchesCounter.get());
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
