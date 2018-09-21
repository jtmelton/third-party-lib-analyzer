package com.jtmelton.tpl.results;

import java.util.*;

public class QueryResult {

  private final String searchTerm;

  private Set<String> jarNames = new HashSet<>();

  private Collection<List<Map<String, Object>>> classChains = new ArrayList<>();

  public QueryResult(String searchTerm) {
    this.searchTerm = searchTerm;
  }

  public String getSearchTerm() {
    return searchTerm;
  }

  public void addJarName(String jarName) {
    jarNames.add(jarName);
  }

  public Collection<String> getJarNames() {
    return Collections.unmodifiableCollection(jarNames);
  }

  public void addClassChain(List<Map<String, Object>> classChain) {
    classChains.add(classChain);
  }

  public Collection<List<Map<String, Object>>> getClassChains() {
    return Collections.unmodifiableCollection(classChains);
  }
}
