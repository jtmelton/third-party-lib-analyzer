package com.jtmelton.tpl.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class Options {

  private boolean singleThreadSearch;

  private String jarsDirectory;

  private String classesDirectory;

  private String dbDirectory;

  private int threads;

  private int searchTimeout;

  private boolean excludeTestDirs = true;

  private Collection<String> depExclusions = new ArrayList<>();

  public boolean isSingleThreadSearch() {
    return singleThreadSearch;
  }

  public void setSingleThreadSearch(boolean singleThreadSearch) {
    this.singleThreadSearch = singleThreadSearch;
  }

  public String getJarsDirectory() {
    return jarsDirectory;
  }

  public void setJarsDirectory(String jarsDirectory) {
    this.jarsDirectory = jarsDirectory;
  }

  public String getClassesDirectory() {
    return classesDirectory;
  }

  public void setClassesDirectory(String classesDirectory) {
    this.classesDirectory = classesDirectory;
  }

  public String getDbDirectory() {
    return dbDirectory;
  }

  public void setDbDirectory(String dbDirectory) {
    this.dbDirectory = dbDirectory;
  }

  public int getThreads() {
    return threads;
  }

  public void setThreads(int threads) {
    this.threads = threads;
  }

  public int getSearchTimeout() {
    return searchTimeout;
  }

  public void setSearchTimeout(int searchTimeout) {
    this.searchTimeout = searchTimeout;
  }

  public boolean isExcludeTestDirs() {
    return excludeTestDirs;
  }

  public void setExcludeTestDirs(boolean excludeTestDirs) {
    this.excludeTestDirs = excludeTestDirs;
  }

  public void addDepExclusion(String exclusion) {
    depExclusions.add(exclusion);
  }

  public Collection<String> getDepExclusions() {
    return Collections.unmodifiableCollection(depExclusions);
  }
}
