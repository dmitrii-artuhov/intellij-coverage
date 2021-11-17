/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

package com.intellij.rt.coverage.report;

import com.intellij.rt.coverage.report.data.Module;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Main {
  public static void main(String[] argsList) {
    try {
      final ReporterArgs args = ReporterArgs.from(argsList);

      final Reporter reporter = new Reporter(args.modules);

      boolean fail = false;

      final File xmlFile = args.xmlFile;
      if (xmlFile != null) {
        try {
          reporter.createXMLReport(xmlFile);
        } catch (IOException e) {
          fail = true;
          System.err.println("XML generation failed.");
          e.printStackTrace();
        }
      }

      final File htmlDir = args.htmlDir;
      if (htmlDir != null) {
        final List<File> sources = new ArrayList<File>();
        for (Module module : args.modules) {
          sources.addAll(module.getSources());
        }
        try {
          reporter.createHTMLReport(htmlDir, sources);
        } catch (IOException e) {
          fail = true;
          System.err.println("HTML generation failed.");
          e.printStackTrace();
        }
      }

      if (args.xmlFile == null && args.htmlDir == null) {
        System.err.println("At least one format must be used: XML, HTML.");
        fail = true;
      }

      if (fail) {
        System.exit(1);
      }
    } catch (ReporterArgs.ArgParseException e) {
      e.printStackTrace(System.err);

      for (String arg : argsList) {
        System.err.println(arg);
      }

      System.err.println();
      System.err.println(ReporterArgs.getHelpString());
      System.exit(1);
    }

  }
}
