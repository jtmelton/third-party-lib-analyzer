package com.jtmelton.tpl.report;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class JsonReporter implements IReporter {

  private static final Logger LOG = LoggerFactory.getLogger(JsonReporter.class);

  private JSONObject jsonRoot = new JSONObject();

  private JSONArray relationships = new JSONArray();

  private JSONArray currentChains;

  private JSONObject currentChainEntry;

  public JsonReporter() {
    jsonRoot.put("DependencyRelationships", relationships);
  }

  @Override
  public void preProcess(Collection<String> jars) {
    JSONObject matchedJar = new JSONObject();
    currentChains = new JSONArray();

    matchedJar.put("chains", currentChains);
    matchedJar.put("jars", jars);

    relationships.put(matchedJar);
  }

  @Override
  public void chainEntryStart() {
    currentChainEntry = new JSONObject();
    JSONArray chain = new JSONArray();
    currentChainEntry.put("chain", chain);
  }

  @Override
  public void chainEntryEnd() {
    currentChains.put(currentChainEntry);
  }

  @Override
  public void addChainEntryUserClass(String className) {
    currentChainEntry.put("userClass", className);
  }

  @Override
  public void addChainLink(String className, Collection<String> jars) {
    JSONObject link = new JSONObject();
    link.put("class", className);
    link.put("jars", jars);

    currentChainEntry.getJSONArray("chain").put(link);
  }

  @Override
  public void report(String outputFile) {
    Path outputPath = Paths.get(outputFile + ".json");

    String report = jsonRoot.toString(2);

    LOG.info("Writing JSON results: {}", outputPath.toAbsolutePath());

    try {
      Files.write(outputPath, report.getBytes());
    } catch(IOException ioe) {
      LOG.warn("Failed to write JSON results", ioe);
    }
  }
}
