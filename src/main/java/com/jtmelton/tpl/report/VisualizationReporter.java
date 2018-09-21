package com.jtmelton.tpl.report;

import com.google.gson.stream.JsonWriter;
import org.parboiled.common.FileUtils;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class VisualizationReporter implements IReporter {

  private JsonWriter jsonWriter;

  private BufferedWriter htmlWriter;

  private Map<String, Map<String, Object>> nodes = new HashMap<>();

  private Map<Integer, Integer> clusterIds = new HashMap<>();

  private int clusterIdCounter = 2;

  private int idCounter = 1;

  private Set<String> writtenEdges = new HashSet<>();

  private Map<String, Object> previous = null;

  private final File outputDir;

  private final File graphSrcDir;

  private final File graphVendorDir;

  private final String CSS_STYLE =
          ".col-container {" +
          "    display: flex;" +
          "    width: 100%;" +
          "}" +
          ".col {" +
          "    flex: 1;" +
          "    padding: 16px;" +
          "}";

  public VisualizationReporter(String outputFile) throws IOException {
    outputDir = new File("visualization");
    outputDir.mkdir();

    graphSrcDir = Paths.get(outputDir.getAbsolutePath(), "graph/src").toFile();
    graphSrcDir.mkdirs();

    graphVendorDir = Paths.get(outputDir.getAbsolutePath(), "graph/vendor").toFile();
    graphVendorDir.mkdirs();

    File indexFile = Paths.get(outputDir.getAbsolutePath(), "index.html").toFile();
    htmlWriter = new BufferedWriter(new FileWriter(indexFile));

    htmlWriter.write("<html>");
    htmlWriter.write("<title>TPLA Results</title>");
    htmlWriter.write("<head>");
    htmlWriter.write("<style>");
    htmlWriter.write(CSS_STYLE);
    htmlWriter.write("</style>");
    htmlWriter.write("</head>");
    htmlWriter.write("<body>");
    htmlWriter.write("<font color=\"#1d67e5\"><h3>Results</h3></font>");

    copyGraphResources();
  }

  @Override
  public void preProcess(String searchTerm, Collection<String> jars) throws IOException{
    Path outputPath = Paths.get(outputDir.getAbsolutePath(), searchTerm);
    File outputFile = outputPath.toFile();
    outputFile.mkdir();

    String htmlFileName = searchTerm + ".html";
    copyResource(outputFile, "/visualizer/visualizer.html", htmlFileName);

    outputPath = Paths.get(outputFile.getAbsolutePath(), "visualization-data.js");

    File jsFile = outputPath.toFile();

    Writer fw = new FileWriter(jsFile);
    fw.write("var data = ");

    jsonWriter = new JsonWriter(fw);
    jsonWriter.setIndent("  ");
    jsonWriter.beginObject();
    jsonWriter.name("edges");
    jsonWriter.beginArray();

    htmlWriter.write("<details>");
    htmlWriter.write("<summary><a href=\"");
    htmlWriter.write(Paths.get(searchTerm, htmlFileName).toString());
    htmlWriter.write("\">" + searchTerm + "</a></summary>");
    htmlWriter.write("<div class=\"col-container\">");
    htmlWriter.write("<div class=\"col\" style=\"background-color:#fce897;\">");
    htmlWriter.write("<ul>");
    for(String jar : jars) {
      htmlWriter.write("<li>");
      htmlWriter.write(jar);
      htmlWriter.write("</li>");
    }
    htmlWriter.write("</ul>");
    htmlWriter.write("</div>");

    htmlWriter.write("<div class=\"col\" style=\"background-color:#bdd8fc;\">");
    htmlWriter.write("<ul>");
  }

  @Override
  public void chainEntryStart() {
    previous = null;
  }

  @Override
  public void chainEntryEnd() {

  }

  @Override
  public void addChainEntryUserClass(String className) throws IOException {
    if(!nodes.containsKey(className)) {
      Map<String, Object> node = new HashMap<>();
      node.put("id", idCounter);
      node.put("name", className);
      node.put("cluster", 1);
      node.put("jar", "user classes");

      nodes.put(className, node);
      idCounter++;

      htmlWriter.write("<li>");
      htmlWriter.write(className);
      htmlWriter.write("</li>");
    }
  }

  @Override
  public void addChainLink(String className, Collection<String> jars) throws IOException {
    if(!nodes.containsKey(className)) {
      Map<String, Object> node = new HashMap<>();
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

    Map<String, Object> current = nodes.get(className);

    if(previous != null) {
      int previousId = (int) previous.get("id");
      int currentId = (int) current.get("id");

      if(!writtenEdges.contains(previousId + ":" + currentId)) {
        jsonWriter.beginObject();
        jsonWriter.name("source").value(previousId);
        jsonWriter.name("target").value(currentId);
        jsonWriter.endObject();
        writtenEdges.add(previousId + ":" + currentId);
      }
    }

    previous = current;
  }

  @Override
  public void endProcess() throws IOException {
    jsonWriter.endArray();
    jsonWriter.name("nodes");
    jsonWriter.beginArray();

    for(Map<String, Object> node : nodes.values()) {
      jsonWriter.beginObject();
      jsonWriter.name("id").value((int) node.get("id"));
      jsonWriter.name("cluster").value((int) node.get("cluster"));
      jsonWriter.name("name").value((String) node.get("name"));
      jsonWriter.name("jar").value((String) node.get("jar"));
      jsonWriter.endObject();
    }

    jsonWriter.endArray();
    jsonWriter.endObject();
    jsonWriter.close();

    htmlWriter.write("</ul>");
    htmlWriter.write("</div>");
    htmlWriter.write("</div>");
    htmlWriter.write("</details>");

    reset();
  }

  @Override
  public void report(String outputFile) throws IOException {
    htmlWriter.write("</body>");
    htmlWriter.write("</html>");
    htmlWriter.close();
  }

  private void reset() {
    nodes = new HashMap<>();
    clusterIds = new HashMap<>();
    clusterIdCounter = 2;
    idCounter = 1;
    writtenEdges = new HashSet<>();
    previous = null;
  }

  private void copyGraphResources() throws IOException {
    String src = "/visualizer/graph/src/";
    String vendor = "/visualizer/graph/vendor/";

    Collection<String> jsFiles = new ArrayList<>();
    jsFiles.add("button.js");
    jsFiles.add("edge.js");
    jsFiles.add("graph.js");
    jsFiles.add("legend.js");
    jsFiles.add("node.js");
    jsFiles.add("text.js");
    jsFiles.add("utils.js");

    for(String jsFile : jsFiles) {
      copyResource(graphSrcDir, src + jsFile, jsFile);
    }

    String p5 = "p5.min.js";
    copyResource(graphVendorDir, vendor + p5, p5);
  }

  private void copyResource(File outDir, String internalFile,
                            String outputFile) throws IOException {
    File outputResource = Paths.get(outDir.getAbsolutePath(), outputFile).toFile();
    try (InputStream is = getClass().getResourceAsStream(internalFile);
         OutputStream os = new FileOutputStream(outputResource)) {

      FileUtils.copyAll(is, os);
    }
  }
}
