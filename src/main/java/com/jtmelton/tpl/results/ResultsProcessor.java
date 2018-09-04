package com.jtmelton.tpl.results;

import com.jtmelton.tpl.report.IReporter;
import org.neo4j.graphdb.GraphDatabaseService;

import java.util.*;

import static com.jtmelton.tpl.utils.QueryUtil.findOwningJarNames;

public class ResultsProcessor {

  private final GraphDatabaseService graphDb;

  private final Collection<IReporter> reporters = new ArrayList<>();

  public ResultsProcessor(GraphDatabaseService graphDb) {
    this.graphDb = graphDb;
  }

  public void registerReporter(IReporter reporter) {
    reporters.add(reporter);
  }

  public void generateReports(String outputFile) {
    reporters.forEach(r -> r.report(outputFile));
  }

  public void process(QueryResult results) {
    reporters.forEach(r -> r.preProcess(results.getJarNames()));

    Set<String> processedNodes = new HashSet<>();

    result: for(List<String> result : results.getClassChains()) {

      reporters.forEach(IReporter::chainEntryStart);

      //Don't care about element 0 which contains the matched jar
      for(int i = result.size() - 1;i > 0;i--) {
        String className = result.get(i);

        final Collection<String> jarNames = new HashSet<>();

        //last element is the user class found
        if(i == result.size() - 1) {
          if(processedNodes.contains(className)) {
            continue result;
          }

          reporters.forEach(r -> r.addChainEntryUserClass(className));
          processedNodes.add(className);

          jarNames.add("user");
        } else {
          jarNames.addAll(findOwningJarNames(graphDb, className));
        }

        reporters.forEach(r ->
          r.addChainLink(className, jarNames)
        );
      }

      reporters.forEach(IReporter::chainEntryEnd);
    }
  }
}
