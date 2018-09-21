package com.jtmelton.tpl.report;

import java.io.IOException;
import java.util.Collection;

public interface IReporter {
  void preProcess(String searchTerm, Collection<String> jars) throws IOException;
  void endProcess() throws IOException;
  void chainEntryStart() throws IOException;
  void chainEntryEnd() throws IOException;
  void addChainEntryUserClass(String className) throws IOException;
  void addChainLink(String className, Collection<String> jars) throws IOException;
  void report(String outputFile) throws IOException;
}
