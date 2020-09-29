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

  @Argument(value = "userClassNames",
          description = "Comma delimited user class name search terms for finding dependencies")
  private static String[] classNames = null;

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

  @Argument(value = "outputDir",
      description = "Dir to write dependency relationships report to")
  private static String outputDir = "output";

  @Argument(value = "searchOnly",
      description = "Search existing DB without building/updating it")
  private static boolean searchOnly = false;

  @Argument(value = "searchUnusedOnly",
      description = "Search for jars that are not potentially unused")
  private static boolean searchUnusedOnly = false;

  @Argument(value = "searchJarExclusions",
      description = "Comma delimited regex for excluding jars from search unused or used searches. " +
              "Inclusions filter first, then exclusions filter the returned subset")
  private static String[] searchJarExclusions = new String[]{};

  @Argument(value = "searchJarInclusions",
      description = "Comma delimited regex for which specific jars should be checked unused or used searches. " +
              "Inclusions filter first, then exclusions filter the returned subset")
  private static String[] searchJarInclusions = new String[]{};

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

  @Argument(value = "stdoutReporter",
      description = "Enable stdout reporter. Has memory impact if you searches eat up a lot of memory")
  private static boolean stdoutReporter = false;

  @Argument(value = "filterResults",
      description = "Enable filtering on results so only one dependency chain from user class to jar is present per jar")
  private static boolean filterResults = false;

  @Argument(value = "exactMatch",
          description = "Enables exact matching for searches. They are contains searches by default")
  private static boolean exactMatch = false;

  public static void main(String[] args) {
    new Cli().parseArgs(args);

    if(searchOnly && searchUnusedOnly) {
      LOG.error("Only one type of search is allowed at a time. " +
              "Either use -searchOnly or -searchUnusedOnly.");
      return;
    }

    Options options = new Options();
    options.setJarsDirectory(jarsDirectory);
    options.setClassesDirectory(classesDirectory);
    options.setDbDirectory(dbDirectory);
    options.setThreads(threads);
    options.setOutputDir(outputDir);
    options.setExactMatch(exactMatch);
    options.setSearchDepth(searchDepth);
    options.setSingleThreadSearch(searchThreads);
    options.setSearchTimeout(searchTimeout);
    options.setExcludeTestDirs(excludeTestDirs);
    options.setFilterResults(filterResults);
    Arrays.asList(depExclusions).forEach(options::addDepExclusion);
    Arrays.asList(searchJarExclusions).forEach(options::addSearchJarExclusion);
    Arrays.asList(searchJarInclusions).forEach(options::addSearchJarInclusion);

    ThirdPartyLibraryAnalyzer analyzer = new ThirdPartyLibraryAnalyzer(options);

    try {
      // TODO: Allow for all search types in one pass
      if(!searchOnly && !searchUnusedOnly) {
        analyzer.buildDependencyGraph(options);
      }

      if(jarNames != null && searchOnly) {
        try {
          analyzer.registerReporter(new JsonReporter(outputDir));
          analyzer.registerReporter(new VisualizationReporter(outputDir));
        } catch(IOException ioe) {
          LOG.warn("Failed to register reporters", ioe);
        }

        if(stdoutReporter) {
          analyzer.registerReporter(new StdOutReporter());
        }

        Collection<String> jarNamesList = Arrays.asList(jarNames);
        analyzer.reportAffectedClasses(jarNamesList, options);
      }

      if(classNames != null && searchOnly) {
        analyzer.registerReporter(new JsonReporter(outputDir));
        Collection<String> classNamesList = Arrays.asList(classNames);
        analyzer.reportDependencies(classNamesList, options);
      }

      if(jarNames == null && classNames == null && searchOnly) {
        LOG.warn("Missing args -jarNames or -userClassNames");
      }

      if(searchUnusedOnly) {
        analyzer.reportUnusedJars(options);
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
