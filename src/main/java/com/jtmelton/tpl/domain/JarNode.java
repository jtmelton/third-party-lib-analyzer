package com.jtmelton.tpl.domain;

import org.neo4j.ogm.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

@NodeEntity
public class JarNode {
  @Id
  @GeneratedValue
  private Long id;

  @Property(name="version")
  private String version;

  @Property(name="name")
  private String name;

  @Relationship(type="classes", direction=Relationship.UNDIRECTED)
  private Collection<ClassNode> classNodes = new ArrayList<>();

  public JarNode() { }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
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
