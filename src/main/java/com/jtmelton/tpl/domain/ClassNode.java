package com.jtmelton.tpl.domain;

import org.neo4j.ogm.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

@NodeEntity
public class ClassNode {
  @Id
  @GeneratedValue
  private Long id;

  @Property(name="name")
  private String name;

  @Property(name="custom")
  private boolean custom;

  @Relationship(type="classesDependedOn")
  private Collection<ClassNode> dependedClasses = new ArrayList<>();

  private String jarName;

  public ClassNode() { }

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

  public boolean isCustom() {
    return custom;
  }

  public void setCustom(boolean custom) {
    this.custom = custom;
  }

  public void addDependedClass(ClassNode classNode) {
    dependedClasses.add(classNode);
  }

  public Collection<ClassNode> getDependedClasses() {
    return Collections.unmodifiableCollection(dependedClasses);
  }

  public String getJarName() {
    return jarName;
  }

  public void setJarName(String jarName) {
    this.jarName = jarName;
  }
}
