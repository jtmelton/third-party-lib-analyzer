package com.jtmelton.tpl.utils;

import com.jtmelton.tpl.results.QueryResult;
import com.jtmelton.tpl.domain.ClassNode;
import com.jtmelton.tpl.domain.JarNode;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryExecutionException;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class QueryUtil {

  private static final Logger LOG = LoggerFactory.getLogger(QueryUtil.class);

  private static final String JAR_TO_CLASS_QUERY = "MATCH (jar:Jar) WHERE jar.name CONTAINS { `searchTerm` } WITH jar " +
        "MATCH path = ((jar)<-[:classes]-(c1:Class)<-[:classesDependedOn*..%s]-(c2:Class)) " +
        "WHERE c2.custom = true RETURN extract(n IN nodes(path) | n.name) AS extracted, jar.name";

  private static final String CLASS_TO_OWNING_JAR_QUERY = "MATCH (class:Class) WHERE class.name = { `name` } WITH class " +
        "MATCH (class)-[:classes]-(jar:Jar) RETURN jar.name";

  private static final String WRITE_JAR_QUERY = "CREATE (n:Jar { name: { `name` } }) RETURN ID(n)";

  private static final String WRITE_CLASS_QUERY = "CREATE (n:Class { name: { `name` }, custom: { `custom` } }) RETURN ID(n)";

  private static final String CLASS_JAR_RELATIONSHIP_QUERY = "MATCH (jar:Jar), (class:Class) WHERE ID(jar) = { `jarID` } " +
          "AND ID(class) = { `classID` } CREATE (jar)<-[rel:classes]-(class)";

  private static final String CLASS_DEPCLASS_RELATIONSHIP_QUERY = "MATCH (class:Class), (dep:Class) " +
          "WHERE ID(class) = { `classID` } AND ID(dep) = { `depID` } CREATE (class)-[rel:classesDependedOn]->(dep)";

  public static QueryResult findAffectedUserClasses(GraphDatabaseService graphDb,
                                                                       String searchTerm, String depth) {
    QueryResult results = new QueryResult();

    try(Transaction tx = graphDb.beginTx()) {
      Map<String, Object> params = new HashMap<>();
      params.put("searchTerm", searchTerm);

      String query = String.format(JAR_TO_CLASS_QUERY, depth);
      Result result = graphDb.execute(query, params);

      while(result.hasNext()) {
        Map<String, Object> map = result.next();
        List<String> chain = (List<String>) map.get("extracted");
        String jarName = (String) map.get("jar.name");

        results.addJarName(jarName);
        results.addClassChain(chain);
      }

      tx.success();
    } catch (QueryExecutionException qee) {
      LOG.warn("Jar to affected user classes query failed", qee);
    }

    return results;
  }

  public static Collection<String> findOwningJarNames(GraphDatabaseService graphDb, String className) {
    Set<String> jarNames = new HashSet<>();

    try(Transaction tx = graphDb.beginTx()) {
      Map<String, Object> params = new HashMap<>();
      params.put("name", className);

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

      Result result = graphDb.execute(WRITE_CLASS_QUERY, params);
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

      graphDb.execute(CLASS_DEPCLASS_RELATIONSHIP_QUERY, params);
      tx.success();
    } catch (QueryExecutionException qee) {
      LOG.warn("Class to dependency class relationship query failed", qee);
    }
  }
}
