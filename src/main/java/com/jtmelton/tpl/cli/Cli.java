package com.jtmelton.tpl.cli;

import com.jtmelton.tpl.ThirdPartyLibraryAnalyzer;
import com.jtmelton.tpl.report.JsonReporter;
import com.jtmelton.tpl.report.StdOutReporter;
import com.jtmelton.tpl.report.VisualizationReporter;
import com.sampullara.cli.Argument;
import com.sampullara.cli.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

public class Cli {

  private static final Logger LOG = LoggerFactory.getLogger(Cli.class);

  @Argument(value = "jarsDirectory",
      required = true,
      description = "Directory containing 3rd/4th party library jars for analysis")
  private static String jarsDirectory = null;

  @Argument(value = "classesDirectory",
      required = true,
      description = "Directory containing java classes (class files) for analysis")
  private static String classesDirectory = null;

  @Argument(value = "dbDirectory",
      required = true,
      description = "Directory containing graph database")
  private static String dbDirectory = null;

  @Argument(value = "jarNames",
      description = "Comma delimited Jar name search terms for finding affected user classes")
  private static String[] jarNames = null;

  @Argument(value = "searchDepth",
      description = "How deep to search graph DB for Jar->user class. Defaults to 5")
  private static String searchDepth = "5";

  @Argument(value = "outputFile",
      description = "File containing output with dependency relationships")
  private static String outputFile = "output";

  @Argument(value = "searchOnly",
      description = "Search existing DB without building/updating it")
  private static boolean searchOnly = false;

  @Argument(value ="threads",
      description = "Number of threads to use for graph construction. Defaults to 5")
  private static Integer threads = 5;

  @Argument(value ="singleThreadSearch",
      description = "Use only one thread for search. Helps on memory usage. Default is 2 threads")
  private static boolean searchThreads = false;

  @Argument(value = "searchTimeout",
      description = "Search Timeout in minutes before killing thread and lowering depth. Default is 60 min")
  private static Integer searchTimeout = 60;

  @Argument(value = "excludeTestDirs",
      description = "Excludes test dirs from relationship analysis")
  private static boolean excludeTestDirs = false;

  @Argument(value = "depExclusions",
      description = "Comma delimited regex for excluding jar dependencies from analysis")
  private static String[] depExclusions = new String[]{};

  public static void main(String[] args) {
    new Cli().parseArgs(args);

    Options options = new Options();
    options.setJarsDirectory(jarsDirectory);
    options.setClassesDirectory(classesDirectory);
    options.setDbDirectory(dbDirectory);
    options.setThreads(threads);
    options.setSingleThreadSearch(searchThreads);
    options.setSearchTimeout(searchTimeout);
    options.setExcludeTestDirs(excludeTestDirs);
    Arrays.asList(depExclusions).forEach(options::addDepExclusion);

    ThirdPartyLibraryAnalyzer analyzer = new ThirdPartyLibraryAnalyzer(options);

    try {
      if(!searchOnly) {
        analyzer.buildDependencyGraph();
      }

      if(jarNames != null) {
        try {
          analyzer.registerReporter(new JsonReporter(outputFile));
          analyzer.registerReporter(new VisualizationReporter(outputFile));
        } catch(IOException ioe) {
          LOG.warn("Failed to register reporters", ioe);
        }

        analyzer.registerReporter(new StdOutReporter());

        Collection<String> jarNamesList = Arrays.asList(jarNames);
        analyzer.reportAffectedClasses(jarNamesList, searchDepth, outputFile);
      }

      if(jarNames == null && searchOnly) {
        LOG.warn("Missing arg -jarNames");
      }
    } catch(InterruptedException ie) {
      LOG.error("Process interrupted while waiting for threads to complete", ie);
    } catch (IOException ioe) {
      LOG.error("Error accessing file system", ioe);
    }
  }

  private void parseArgs(String[] args) {
    Args.parse(this, args);
  }

}
