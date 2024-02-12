/*
 * Copyright 2000-2024 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.rt.coverage.instrumentation.data;

import com.intellij.rt.coverage.data.*;
import com.intellij.rt.coverage.data.instructions.InstructionsUtil;
import com.intellij.rt.coverage.instrumentation.InstrumentationOptions;
import com.intellij.rt.coverage.util.ArrayUtil;
import com.intellij.rt.coverage.util.ClassNameUtil;
import com.intellij.rt.coverage.util.LineMapper;
import com.intellij.rt.coverage.util.StringsPool;
import com.intellij.rt.coverage.util.classFinder.ClassFinder;
import org.jetbrains.coverage.gnu.trove.TIntHashSet;
import org.jetbrains.coverage.gnu.trove.TIntProcedure;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ProjectContext {
  private final InstrumentationOptions myOptions;
  private final ClassFinder myClassFinder;

  private final StringsPool myStringPool = new StringsPool();
  private final FilteredMethodStorage myAnnotationStorage = new FilteredMethodStorage();

  /**
   * Set of lines that were ignored during instrumentation.
   * Storing this lines helps to correctly merge when a class has inline functions.
   */
  private volatile Map<String, TIntHashSet> myIgnoredLines;
  private volatile Map<String, FileMapData[]> myLinesMap;

  public ProjectContext(InstrumentationOptions options) {
    this(options, new ClassFinder(options.includePatterns, options.excludePatterns));
  }

  public ProjectContext(InstrumentationOptions options, ClassFinder classFinder) {
    myOptions = options;
    myClassFinder = classFinder;
  }

  public InstrumentationOptions getOptions() {
    return myOptions;
  }

  public FilteredMethodStorage getFilteredStorage() {
    return myAnnotationStorage;
  }

  public String getFromPool(String s) {
    return myStringPool.getFromPool(s);
  }

  public ClassFinder getClassFinder() {
    return myClassFinder;
  }

  public void addLineMaps(String className, FileMapData[] fileDatas) {
    Map<String, FileMapData[]> linesMap = myLinesMap;
    if (linesMap == null) {
      synchronized (this) {
        linesMap = myLinesMap;
        if (linesMap == null) {
          linesMap = new ConcurrentHashMap<String, FileMapData[]>();
          myLinesMap = linesMap;
        }
      }
    }
    linesMap.put(className, fileDatas);
  }

  public void addIgnoredLines(String className, TIntHashSet lines) {
    if (lines == null || lines.isEmpty()) return;
    Map<String, TIntHashSet> ignoredLines = myIgnoredLines;
    if (ignoredLines == null) {
      synchronized (this) {
        ignoredLines = myIgnoredLines;
        if (ignoredLines == null) {
          ignoredLines = new ConcurrentHashMap<String, TIntHashSet>();
          myIgnoredLines = ignoredLines;
        }
      }
    }
    ignoredLines.put(className, lines);
  }

  public void finalizeCoverage(ProjectData projectData) {
    applyLineMappings(projectData);
    dropIgnoredLines(projectData);
  }

  /**
   * Remove all lines that are generated by inline.
   * Should be called only in case when hits of these lines are out of interest,
   * for example when analysing unloaded classes.
   */
  public void dropLineMappings(ProjectData projectData) {
    if (myLinesMap != null) {
      for (Map.Entry<String, FileMapData[]> entry : myLinesMap.entrySet()) {
        ClassData classData = projectData.getClassData(entry.getKey());
        dropLineMappings(projectData, classData, entry.getValue());
      }
    }
    dropIgnoredLines(projectData);
  }

  /**
   * Update coverage data internally stored in arrays.
   */
  public void applyHits(ProjectData projectData) {
    for (ClassData data : projectData.getClassesCollection()) {
      data.applyHits();
    }
  }

  public void dropLineMappings(ProjectData projectData, ClassData classData) {
    if (myLinesMap != null) {
      FileMapData[] mappings = myLinesMap.get(classData.getName());
      if (mappings != null) {
        dropLineMappings(projectData, classData, mappings);
      }
    }
    if (myIgnoredLines != null) {
      TIntHashSet ignoredLines = myIgnoredLines.get(classData.getName());
      if (ignoredLines != null) {
        dropIgnoredLines(classData, ignoredLines);
      }
    }
  }

  public void dropIgnoredLines(ProjectData projectData) {
    if (myIgnoredLines == null) return;
    for (Map.Entry<String, TIntHashSet> e : myIgnoredLines.entrySet()) {
      ClassData classData = projectData.getClassData(e.getKey());
      if (classData == null) continue;
      dropIgnoredLines(classData, e.getValue());
    }
  }

  /**
   * Apply line mappings: move hits from original line in bytecode to the mapped line.
   */
  private void applyLineMappings(ProjectData projectData) {
    if (myLinesMap == null) return;
    for (Map.Entry<String, FileMapData[]> entry : myLinesMap.entrySet()) {
      final String className = entry.getKey();
      final ClassData classData = projectData.getClassData(className);
      final FileMapData[] fileData = entry.getValue();
      //postpone process main file because its lines would be reset and next files won't be processed correctly
      FileMapData mainData = null;
      for (FileMapData aFileData : fileData) {
        final String mappedClassName = getFromPool(aFileData.getClassName());
        if (mappedClassName.equals(className)) {
          mainData = aFileData;
          continue;
        }
        final ClassData classInfo;
        if (shouldIncludeClass(mappedClassName)) {
          classInfo = projectData.getOrCreateClassData(mappedClassName);
          if (getOptions().isSaveSource && classInfo.getSource() == null) {
            classInfo.setSource(aFileData.getFileName());
          }
        } else {
          // `classData` SMAP may not contain mapping to itself,
          // so it's better to make sure we fairly apply this mapping
          // otherwise `classData` may contain inline generated lines
          classInfo = new ClassData(mappedClassName);
        }
        applyLineMappings(aFileData.getLines(), classInfo, classData);
        InstructionsUtil.applyInstructionsSMAP(projectData, aFileData.getLines(), classInfo, classData);
      }

      if (mainData != null) {
        applyLineMappings(mainData.getLines(), classData, classData);
        InstructionsUtil.applyInstructionsSMAP(projectData, mainData.getLines(), classData, classData);
      }
    }
  }

  /**
   * Apply line mappings: move hits from original line in bytecode to the mapped line.
   *
   * @param linesMap        line mappings from target class to source class
   * @param sourceClassData the class to which the mapped lines are moved
   * @param targetClassData the class which initially contains the mapped lines,
   *                        at the end of this method all mapped lines in this class are set to null
   */
  private static void applyLineMappings(LineMapData[] linesMap, ClassData sourceClassData, ClassData targetClassData) {
    sourceClassData.resetLines(new BasicLineMapper().mapLines(linesMap, sourceClassData, targetClassData));
  }

  private static void dropLineMappings(ProjectData projectData, ClassData classData, FileMapData[] mappings) {
    LineMapper.dropMappedLines(mappings, classData.getLines(), classData.getName());
    InstructionsUtil.dropMappedLines(projectData, classData.getName(), mappings);
  }

  private static void dropIgnoredLines(final ClassData classData, TIntHashSet ignoredLines) {
    ignoredLines.forEach(new TIntProcedure() {
      public boolean execute(int line) {
        ArrayUtil.safeStore(classData.getLines(), line, null);
        return true;
      }
    });
  }

  private boolean shouldIncludeClass(String className) {
    if (ClassNameUtil.matchesPatterns(className, getOptions().excludePatterns)) return false;
    List<Pattern> includePatterns = getOptions().includePatterns;
    return includePatterns == null || includePatterns.isEmpty() || ClassNameUtil.matchesPatterns(className, includePatterns);
  }

  private static class BasicLineMapper extends LineMapper<LineData> {

    @Override
    protected LineData createNewLine(LineData targetLine, int line) {
      return new LineData(line, targetLine.getMethodSignature());
    }

    @Override
    protected LineData[] createArray(int size) {
      return new LineData[size];
    }

    @Override
    protected LineData[] getLines(ClassData classData) {
      return (LineData[]) classData.getLines();
    }
  }
}
