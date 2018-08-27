package com.jtmelton.tpl.report;

import org.neo4j.ogm.model.Result;

import java.util.Collection;

public interface IReporter {
  void processResult(Result results, Collection<String> jarNames);
  String getReport();
}
