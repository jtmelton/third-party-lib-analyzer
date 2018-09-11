package com.jtmelton.tpl.results;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
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

    Multimap<String, Integer> processedChains = HashMultimap.create();

    result: for(List<String> result : results.getClassChains()) {

      reporters.forEach(IReporter::chainEntryStart);

      //Don't care about element 0 which contains the matched jar
      for(int i = result.size() - 1;i > 0;i--) {
        String className = result.get(i);

        final Collection<String> jarNames = new HashSet<>();

        //last element is the user class found
        if(i == result.size() - 1) {
          int jarHash = result.get(0).hashCode();

          if(processedChains.get(className).contains(jarHash)) {
            continue result;
          }

          reporters.forEach(r -> r.addChainEntryUserClass(className));
          processedChains.put(className, result.get(0).hashCode());

          jarNames.add("user");
        } else {
          Collection<String> jars = findOwningJarNames(graphDb, className);

          String userClass = result.get(result.size() - 1);
          jars.forEach(j -> processedChains.put(userClass, j.hashCode()));

          jarNames.addAll(jars);
        }

        reporters.forEach(r ->
          r.addChainLink(className, jarNames)
        );
      }

      reporters.forEach(IReporter::chainEntryEnd);
    }
  }
}
