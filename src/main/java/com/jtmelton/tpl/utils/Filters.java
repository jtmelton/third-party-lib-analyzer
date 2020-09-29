package com.jtmelton.tpl.utils;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Filters {
  public static Predicate<Map<String, Object>> searchJarInclude(Collection<String> inclusions) {
    return j -> {
      if(inclusions.isEmpty()) {
        return true;
      }

      String name = (String) j.get("name");
      for(String regex : inclusions) {
        if(name.matches(regex)) {
          return true;
        }
      }

      return false;
    };
  }

  public static Predicate<Map<String, Object>> searchJarExclude(Collection<String> exclusions) {
    return j -> {
      if(exclusions.isEmpty()) {
        return true;
      }

      String name = (String) j.get("name");
      for(String regex : exclusions) {
        if(name.matches(regex)) {
          return false;
        }
      }

      return true;
    };
  }

  public static Predicate<Path> depExclude(Collection<String> exclusions) {
    return p -> {
      for(String exclusion : exclusions) {
        Pattern pattern = Pattern.compile(exclusion);
        Matcher matcher = pattern.matcher(p.toString());

        if(matcher.matches()) {
          return false;
        }
      }

      return true;
    };
  }

  public static Predicate<Path> filterTestDirs() {
    return p -> {
      String path = p.toAbsolutePath().toFile().getAbsolutePath();
      if(path.contains("/")) {
        return !path.contains("/test/");
      } else if(path.contains("\\\\")) {
        return !path.contains("\\\\test\\\\");
      }
      return true;
    };
  }

  public static Predicate<Map<String, Object>> contains(String searchTerm) {
    return p -> ((String) p.get("name")).contains(searchTerm);
  }

  public static Predicate<Map<String, Object>> equals(String searchTerm) {
    return p -> p.get("name").equals(searchTerm);
  }
}
