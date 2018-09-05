package com.jtmelton.tpl.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class JarNode {

  private Long id;

  private String name;

  private Collection<ClassNode> classNodes = new ArrayList<>();

  public JarNode() { }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void addClassFile(ClassNode classNode) {
    classNodes.add(classNode);
  }

  public Collection<ClassNode> getClassNodes() {
    return Collections.unmodifiableCollection(classNodes);
  }
}
