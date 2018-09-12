package com.jtmelton.tpl.report;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class VisualizationReporter implements IReporter {

  private static final Logger LOG = LoggerFactory.getLogger(VisualizationReporter.class);

  private Map<String, JSONObject> nodes = new HashMap<>();

  private Map<String, JSONObject> edges = new HashMap<>();

  private Map<Integer, Integer> clusterIds = new HashMap<>();

  private int clusterIdCounter = 2;

  private int idCounter = 1;

  private JSONObject previous = null;

  @Override
  public void preProcess(Collection<String> jars) {

  }

  @Override
  public void chainEntryStart() {
    previous = null;
  }

  @Override
  public void chainEntryEnd() {

  }

  @Override
  public void addChainEntryUserClass(String className) {
    if(!nodes.containsKey(className)) {
      JSONObject node = new JSONObject();
      node.put("id", idCounter);
      node.put("name", className);
      node.put("cluster", 1);
      node.put("jar", "user classes");

      nodes.put(className, node);
      idCounter++;
    }
  }

  @Override
  public void addChainLink(String className, Collection<String> jars) {
    if(!nodes.containsKey(className)) {
      JSONObject node = new JSONObject();
      node.put("id", idCounter);
      node.put("name", className);

      int jarsHash = jars.hashCode();

      if(clusterIds.containsKey(jarsHash)) {
        node.put("cluster", clusterIds.get(jarsHash));
      } else {
        node.put("cluster", clusterIdCounter);
        clusterIds.put(jarsHash, clusterIdCounter);
        clusterIdCounter++;
      }

      String jar = jars.iterator().next();

      String splitter = File.separator.equals("\\") ? "\\\\" : File.separator;

      String[] jarSplit = jar.split(splitter);
      node.put("jar", jarSplit[jarSplit.length - 1]);

      nodes.put(className, node);
      idCounter++;
    }

    JSONObject current = nodes.get(className);

    if(previous != null) {
      JSONObject edge = new JSONObject();
      int previousId = (int) previous.get("id");
      int currentId = (int) current.get("id");
      edge.put("source", previousId);
      edge.put("target", currentId);

      edges.put(previousId + ":" + currentId, edge);
    }

    previous = current;
  }

  @Override
  public void report(String outputFile) {
    JSONObject root = new JSONObject();
    JSONArray nodeArray = new JSONArray();
    JSONArray edgeArray = new JSONArray();

    nodes.values().forEach(nodeArray::put);
    root.put("nodes", nodeArray);

    edges.values().forEach(edgeArray::put);
    root.put("edges", edgeArray);

    Path outputPath = Paths.get(outputFile + "-alchemy.js");

    String report = root.toString(2);

    String finalReport = "var data = " + report;

    LOG.info("Writing hive JSON results: {}", outputPath.toAbsolutePath());

    try {
      Files.write(outputPath, finalReport.getBytes());
    } catch(IOException ioe) {
      LOG.warn("Failed to write hive JSON results", ioe);
    }
  }
}
