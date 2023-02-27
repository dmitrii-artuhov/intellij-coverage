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

package testData.utilClass.throwing;

// classes: ALL
// extra args: -Dcoverage.ignore.private.constructor.util.class=true
// patterns: testData.utilClass.throwing.*
// calculate unloaded: true

public class Test {
  private Test() {
    throw new RuntimeException();
  }

  public static void main(String[] args) {} // coverage: FULL

  public static class UnusedJavaTest {
    private UnusedJavaTest() {
      throw new UnsupportedOperationException();
    }

    public static void help() {
    } // coverage: NONE
  }
}
