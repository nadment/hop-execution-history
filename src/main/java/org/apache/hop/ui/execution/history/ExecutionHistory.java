/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hop.ui.execution.history;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.apache.hop.execution.ExecutionType;

public class ExecutionHistory {
  @Getter private final String name;
  @Getter private final List<ExecutionRun> runs;
  @Getter private final ExecutionType type;
  @Getter private List<String> componentNames;

  /** Maximum number of execution runs. */
  protected static final int RUN_LIMIT = 20;

  public ExecutionHistory(ExecutionType type, String name) {
    this.type = type;
    this.name = name;
    this.runs = new ArrayList<>();
    this.componentNames = new ArrayList<>();
  }

  public ExecutionRun getRun(int index) {
    return runs.get(index);
  }

  public void addComponentIfAbsent(String name) {
    if (!componentNames.contains(name)) {
      componentNames.add(name);
    }
  }

  /** Find the longest execution time */
  public long getMaxDuration() {
    long maxDuration = 0;
    for (ExecutionRun run : runs) {
      maxDuration = Math.max(maxDuration, run.getDuration());
    }
    return maxDuration;
  }
}
