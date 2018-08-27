package com.jtmelton.tpl.utils;

import com.google.common.collect.Multimap;
import com.jtmelton.tpl.domain.ClassNode;
import com.jtmelton.tpl.domain.JarNode;
import javassist.ClassPool;
import javassist.CtClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class JavassistUtil {

  private static final Logger LOG = LoggerFactory.getLogger(JavassistUtil.class);

  public static Set<String> collectClassFiles(Collection<Path> paths) throws IOException {
    Set<String> classes = new HashSet<>();
    ClassPool classPool = new ClassPool();

    for (Path path : paths) {
      byte[] bFile = java.nio.file.Files.readAllBytes(path);

      try(InputStream is = new ByteArrayInputStream(bFile)) {
        CtClass ctClass = classPool.makeClass(is);
        classes.add(ctClass.getName());
      }
    }

    return classes;
  }

  public static Collection<ClassNode> analyzeClassFiles(Collection<Path> paths,
                                                        Collection<String> customClasses,
                                                        Multimap<String, String> classToUsedClassesMap) throws IOException {
    Collection<ClassNode> classNodes = new ArrayList<>();
    ClassPool classPool = new ClassPool();

    for (Path path : paths) {
      byte[] bFile = java.nio.file.Files.readAllBytes(path);

      try(InputStream is = new ByteArrayInputStream(bFile)) {
        CtClass ctClass = classPool.makeClass(is);
        final Collection<String> referencedClasses = ctClass.getRefClasses();

        ClassNode classNode = new ClassNode();
        classNode.setName(ctClass.getName());
        classNode.setCustom(true);

        Collection<String> filteredClasses = referencedClasses.stream()
                .filter(c -> !customClasses.contains(c) && !classNode.getName().equals(c))
                .collect(Collectors.toList());

        classToUsedClassesMap.putAll(classNode.getName(), filteredClasses);
        classNodes.add(classNode);
      }
    }

    return classNodes;
  }

  public static Collection<JarNode> analyzeJarFiles(Collection<Path> jars,
                                                    Map<String, ClassNode> externalClassNodes,
                                                    Multimap<String, String> classToUsedClassesMap) throws IOException {
    Collection<JarNode> jarNodes = new ArrayList<>();

    jar: for (Path jar : jars) {
      ClassPool classPool = new ClassPool();

      final JarFile artifactJarFile = new JarFile(jar.toFile());
      final Enumeration<JarEntry> jarEntries = artifactJarFile.entries();

      JarNode jarNode = new JarNode();
      jarNode.setName(artifactJarFile.getName());

      while (jarEntries.hasMoreElements()) {
        final JarEntry jarEntry = jarEntries.nextElement();

        if (jarEntry.getName().endsWith(".class")) {
          try (InputStream is = artifactJarFile.getInputStream(jarEntry)) {
            CtClass ctClass = classPool.makeClass(is);
            final Collection<String> referencedClasses = ctClass.getRefClasses();

            ClassNode classNode = externalClassNodes.get(ctClass.getName());

            if(classNode == null) {
              classNode = new ClassNode();
              classNode.setName(ctClass.getName());
              classNode.setCustom(false);
              externalClassNodes.put(classNode.getName(), classNode);
            }

            classToUsedClassesMap.putAll(classNode.getName(), referencedClasses);

            jarNode.addClassFile(classNode);
          } catch (SecurityException se) {
            LOG.warn("Failed to process jar {}", jarNode.getName(), se);
            continue jar;
          }
        }
      }

      jarNodes.add(jarNode);
    }

    return jarNodes;
  }
}
