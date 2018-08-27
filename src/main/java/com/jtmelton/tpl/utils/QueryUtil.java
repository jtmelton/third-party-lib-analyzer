package com.jtmelton.tpl.utils;

import com.jtmelton.tpl.domain.ClassNode;
import com.jtmelton.tpl.domain.JarNode;
import org.neo4j.ogm.cypher.ComparisonOperator;
import org.neo4j.ogm.cypher.Filter;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

import java.util.*;

public class QueryUtil {

  private final static String JAR_TO_CLASS_QUERY = "MATCH (jar:`JarNode`) WHERE jar.`name` CONTAINS { `searchTerm` } WITH jar " +
          "MATCH path = ((jar)-[:`classes`]-(:`ClassNode`)<-[:`classesDependedOn`*..%s]-(class:`ClassNode`)) " +
          "WHERE class.`custom` = true RETURN nodes(path)";

  private static final String CLASS_TO_OWNING_JAR_QUERY = "MATCH (class:`ClassNode`) WHERE ID(class) = { `id` } WITH class " +
          "MATCH (class)-[:classes]-(jar:`JarNode`) RETURN jar";

  public static Result findAffectedUserClasses(Session session, String searchTerm, String depth) {
    Map<String, String> params = new HashMap<>();
    params.put("searchTerm", searchTerm);

    String query = String.format(JAR_TO_CLASS_QUERY, depth);

    return session.query(query, params);
  }

  public static Collection<String> findOwningJarNames(Session session, ClassNode classNode) {
    if(classNode.isCustom()) {
      return Arrays.asList("user");
    }

    Map<String, Long> params = new HashMap<>();
    params.put("id", classNode.getId());

    List<JarNode> jars = (List<JarNode>) session.query(JarNode.class, CLASS_TO_OWNING_JAR_QUERY, params);

    Collection<String> result = new ArrayList<>();
    jars.forEach(j -> result.add(j.getName()));

    return result;
  }

  public static Collection<JarNode> findMatchingJars(Session session, String searchTerm) {
    Filter filter = new Filter("name", ComparisonOperator.CONTAINING, searchTerm);
    return session.loadAll(JarNode.class, filter);
  }

}
