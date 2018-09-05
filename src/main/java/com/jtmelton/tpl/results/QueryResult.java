package com.jtmelton.tpl.results;

import java.util.*;

public class QueryResult {

  private Set<String> jarNames = new HashSet<>();

  private Collection<List<String>> classChains = new ArrayList<>();

  public void addJarName(String jarName) {
    jarNames.add(jarName);
  }

  public Collection<String> getJarNames() {
    return Collections.unmodifiableCollection(jarNames);
  }

  public void addClassChain(List<String> classChain) {
    classChains.add(classChain);
  }

  public Collection<List<String>> getClassChains() {
    return Collections.unmodifiableCollection(classChains);
  }
}
