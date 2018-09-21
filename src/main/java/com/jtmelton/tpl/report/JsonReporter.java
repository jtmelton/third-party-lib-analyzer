package com.jtmelton.tpl.report;

import com.google.gson.stream.JsonWriter;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class JsonReporter implements IReporter {

  private JsonWriter writer;

  public JsonReporter(String outputFile) throws IOException {
    Path outputPath = Paths.get(outputFile + ".json");
    FileWriter fw = new FileWriter(outputPath.toFile());

    writer = new JsonWriter(fw);
    writer.setIndent("  ");
    writer.beginObject();
    writer.name("DependencyRelationships");
    writer.beginArray();
  }

  @Override
  public void preProcess(String searchTerm, Collection<String> jars) throws IOException {
      writer.beginObject();

      writer.name("jars");

      writer.beginArray();
      for(String jar : jars) {
        writer.value(jar);
      }
      writer.endArray();

      writer.name("chains");

      writer.beginArray();
  }

  @Override
  public void chainEntryStart() throws IOException {
    writer.beginObject();
  }

  @Override
  public void chainEntryEnd() throws IOException {
    writer.endArray();
    writer.endObject();
  }

  @Override
  public void addChainEntryUserClass(String className) throws IOException {
    writer.name("userClass").value(className);
    writer.name("chain");
    writer.beginArray();
  }

  @Override
  public void addChainLink(String className, Collection<String> jars) throws IOException {
    writer.beginObject();

    writer.name("class").value(className);

    writer.name("jars");
    writer.beginArray();
    for (String jar : jars) {
      writer.value(jar);
    }
    writer.endArray();

    writer.endObject();
  }

  @Override
  public void endProcess() throws IOException {
    writer.endArray();
    writer.endObject();
  }

  @Override
  public void report(String outputFile) throws IOException {
    writer.endArray();
    writer.endObject();
    writer.close();
  }
}
