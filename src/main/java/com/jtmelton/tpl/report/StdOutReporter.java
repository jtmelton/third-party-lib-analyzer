package com.jtmelton.tpl.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class StdOutReporter implements IReporter {

  private static final Logger LOG = LoggerFactory.getLogger(StdOutReporter.class);

  private final String NEW_LINE = System.getProperty("line.separator");

  private StringBuilder builder = new StringBuilder();

  private int chainCounter;

  @Override
  public void preProcess(Collection<String> jars) {
    builder.append("Search Results");
    builder.append(NEW_LINE);
    builder.append("Jars Matched");
    builder.append(NEW_LINE);

    jars.forEach(j -> {
      builder.append(j);
      builder.append(NEW_LINE);
    });

    builder.append(NEW_LINE);
    builder.append("Class Chains");
    builder.append(NEW_LINE);
  }

  @Override
  public void chainEntryStart() {
    chainCounter = 0;
  }

  @Override
  public void chainEntryEnd() {
    builder.append(NEW_LINE);
  }

  @Override
  public void addChainEntryUserClass(String className) {

  }

  @Override
  public void addChainLink(String className, Collection<String> jars) {
    if(chainCounter > 0) {
      for(int i = 0;i < chainCounter * 2;i++) {
        builder.append(" ");
      }

      builder.append("|_");
    }

    builder.append(className);
    builder.append(NEW_LINE);

    chainCounter++;
  }

  @Override
  public void report(String outputFile) {
    LOG.info(builder.toString());
  }
}
