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
import java.util.Comparator;
import java.util.List;
import lombok.Getter;
import org.apache.hop.execution.ExecutionType;

/**
 * Execution history of a single pipeline or workflow, as displayed by {@link
 * ExecutionHistoryChart}.
 *
 * <p>It holds the most recent {@link ExecutionRun runs} (one chart column each) and the union of
 * the component names (transforms or actions, one chart row each) found across those runs.
 */
public class ExecutionHistory {
  /** Name of the pipeline or workflow. */
  @Getter private final String name;

  /** Execution runs, sorted by the caller (most recent first). Mutable. */
  @Getter private final List<ExecutionRun> runs;

  /** Whether this history belongs to a pipeline or a workflow. */
  @Getter private final ExecutionType type;

  /** Distinct transform or action names found in the runs, in display order. Mutable. */
  @Getter private List<String> componentNames;

  /** Number of execution runs loaded initially, and added by each "Get more history" request. */
  protected static final int RUN_LIMIT = 25;

  /**
   * Creates an empty history.
   *
   * @param type pipeline or workflow
   * @param name name of the pipeline or workflow
   */
  public ExecutionHistory(ExecutionType type, String name) {
    this.type = type;
    this.name = name;
    this.runs = new ArrayList<>();
    this.componentNames = new ArrayList<>();
  }

  /**
   * Returns the run at the given position.
   *
   * @param index zero-based index in {@code getRuns()}
   * @return the run
   * @throws IndexOutOfBoundsException if the index is out of range
   */
  public ExecutionRun getRun(int index) {
    return runs.get(index);
  }

  /**
   * Registers a transform or action name, keeping the list free of duplicates.
   *
   * @param name the component name to add
   */
  public void addComponentIfAbsent(String name) {
    if (!componentNames.contains(name)) {
      componentNames.add(name);
    }
  }

  /**
   * Sorts the runs by registration date, most recent first (runs without a date last), then keeps
   * only the {@code limit} most recent ones.
   *
   * <p>Call it once all locations are loaded, so that the limit applies to the merged runs.
   *
   * @param limit the maximum number of runs to keep
   */
  public void sortAndTruncate(int limit) {
    runs.sort(
        Comparator.comparing(
            ExecutionRun::getRegistrationDate, Comparator.nullsLast(Comparator.reverseOrder())));
    if (runs.size() > limit) {
      runs.subList(limit, runs.size()).clear();
    }
  }

  /**
   * Finds the longest execution time among the runs.
   *
   * @return the maximum run duration in seconds, or 0 if there is no run
   */
  public long getMaxDuration() {
    long maxDuration = 0;
    for (ExecutionRun run : runs) {
      maxDuration = Math.max(maxDuration, run.getDuration());
    }
    return maxDuration;
  }
}
