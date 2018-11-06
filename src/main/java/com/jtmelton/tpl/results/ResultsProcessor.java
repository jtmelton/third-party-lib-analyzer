package com.jtmelton.tpl.results;

import com.jtmelton.tpl.report.IReporter;
import org.neo4j.graphdb.GraphDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

import static com.jtmelton.tpl.utils.QueryUtil.findOwningJarNames;

public class ResultsProcessor {

  private static final Logger LOG = LoggerFactory.getLogger(ResultsProcessor.class);

  private final GraphDatabaseService graphDb;

  private final Collection<IReporter> reporters = new ArrayList<>();

  public ResultsProcessor(GraphDatabaseService graphDb) {
    this.graphDb = graphDb;
  }

  public void registerReporter(IReporter reporter) {
    reporters.add(reporter);
  }

  public void generateReports(String outputFile) {
    reporters.forEach(reporter -> {
      try {
        reporter.report(outputFile);
      } catch (IOException e) {
        LOG.warn("Reporter {} failed", reporter.getClass().toString(), e);
      }
    });
  }

  public void process(QueryResult results) {
    execPreProcess(results);

    for(List<Map<String, Object>> result : results.getClassChains()) {
      execChainEntryStart();

      //Don't care about the first element which contains the matched jar
      for(int i = result.size() - 1;i > 0;i--) {
        String className = (String) result.get(i).get("name");
        long id = (Long) result.get(i).get("id");

        final Collection<String> jarNames = new HashSet<>();

        //last element is the user class found
        if(i == result.size() - 1) {
          execAddChainEntryUserClass(className);
          jarNames.add("user");
        } else {
          Collection<String> jars = findOwningJarNames(graphDb, id);
          jarNames.addAll(jars);
        }

        execAddChainLink(className, jarNames);
      }

      execChainEntryEnd();
    }

    execEndProcess();
  }

  private void execPreProcess(QueryResult results) {
    reporters.forEach(reporter -> {
      try {
        reporter.preProcess(results.getSearchTerm(), results.getJarNames());
      } catch (IOException e) {
        LOG.warn("Reporter {} failed", reporter.getClass().toString(), e);
      }
    });
  }

  private void execChainEntryStart() {
    for (IReporter reporter : reporters) {
      try {
        reporter.chainEntryStart();
      } catch(IOException e) {
        LOG.warn("Reporter {} failed", reporter.getClass().toString(), e);
      }
    }
  }

  private void execAddChainEntryUserClass(String className) {
    reporters.forEach(reporter -> {
      try {
        reporter.addChainEntryUserClass(className);
      } catch (IOException e) {
        LOG.warn("Reporter {} failed", reporter.getClass().toString(), e);
      }
    });
  }

  private void execAddChainLink(String className, Collection<String> jarNames) {
    reporters.forEach(reporter ->
      {
        try {
          reporter.addChainLink(className, jarNames);
        } catch (IOException e) {
          LOG.warn("Reporter {} failed", reporter.getClass().toString(), e);
        }
      }
    );
  }

  private void execChainEntryEnd() {
    for (IReporter reporter : reporters) {
      try {
        reporter.chainEntryEnd();
      } catch (IOException e) {
        LOG.warn("Reporter {} failed", reporter.getClass().toString(), e);
      }
    }
  }

  private void execEndProcess() {
    for (IReporter reporter : reporters) {
      try {
        reporter.endProcess();
      } catch (IOException e) {
        LOG.warn("Reporter {} failed", reporter.getClass().toString(), e);
      }
    }
  }
}
