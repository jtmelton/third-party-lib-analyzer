package com.jtmelton.tpl.report;

import com.jtmelton.tpl.ThirdPartyLibraryAnalyzer;
import com.jtmelton.tpl.domain.ClassNode;
import com.jtmelton.tpl.domain.JarNode;
import org.json.JSONArray;
import org.json.JSONObject;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.jtmelton.tpl.utils.QueryUtil.findOwningJarNames;

public class JsonReporter implements IReporter {

  private final Session session;

  private final String resultKey;

  private JSONObject jsonRoot = new JSONObject();

  private JSONArray relationships = new JSONArray();

  public JsonReporter(Session session, String resultKey) {
    this.session = session;
    this.resultKey = resultKey;

    jsonRoot.put("DependencyRelationships", relationships);
  }

  @Override
  public void processResult(Result results, Collection<String> jarNames) {
    if(!results.iterator().hasNext()) {
      return;
    }

    JSONObject matchedJar = new JSONObject();
    JSONArray chains = new JSONArray();

    matchedJar.put("chains", chains);
    matchedJar.put("jars", jarNames);
    process(results).forEach(chains::put);

    relationships.put(matchedJar);
  }

  @Override
  public String getReport() {
    return jsonRoot.toString(2);
  }

  private Collection<JSONObject> process(Result results) {
    Collection<JSONObject> entries = new ArrayList<>();
    Set<ClassNode> processedNodes = new HashSet<>();

    result: for(Map<String, Object> result : results) {
      ArrayList nodes = (ArrayList) result.get(resultKey);

      JSONObject classEntry = new JSONObject();
      JSONArray chain = new JSONArray();
      classEntry.put("chain", chain);

      for(int i = nodes.size() - 1;i >= 0;i--) {
        if(nodes.get(i) instanceof JarNode) {
          break;
        }

        ClassNode classNode = (ClassNode) nodes.get(i);

        if(classNode.isCustom()) {
          if(processedNodes.contains(classNode)) {
            continue result;
          }

          classEntry.put("userClass", classNode.getName());
          processedNodes.add(classNode);
        }

        JSONObject link = new JSONObject();
        link.put("class", classNode.getName());
        link.put("jars", findOwningJarNames(session, classNode));
        chain.put(link);
      }

      entries.add(classEntry);
    }

    return entries;
  }
}
