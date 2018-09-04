package com.jtmelton.tpl.report;

import java.util.Collection;

public interface IReporter {
  void preProcess(Collection<String> jars);
  void chainEntryStart();
  void chainEntryEnd();
  void addChainEntryUserClass(String className);
  void addChainLink(String className, Collection<String> jars);
  void report(String outputFile);
}
