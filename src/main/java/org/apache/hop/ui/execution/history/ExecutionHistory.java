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
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.apache.hop.execution.IExecutionSelector;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Execution history of a single pipeline or workflow, as displayed by {@link
 * ExecutionHistoryChart}.
 *
 * <p>It holds the most recent {@link ExecutionRun runs} (one chart column each) and the union of
 * the component names (transforms or actions, one chart row each) found across those runs.
 */
@NullMarked
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
  protected static final int RUN_PAGE_SIZE = 25;

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
   * Adds the most recent runs of this pipeline or workflow found in a location.
   *
   * <p>The location is expected to list the most recent executions first. An unreadable execution
   * is logged and skipped, so that it doesn't hide the other executions of the location.
   *
   * @param locationName the name of the execution information location metadata
   * @param location the initialized execution information location
   * @param selector selects the executions of this pipeline or workflow
   * @param loggingInterval maximum delay in milliseconds between two state updates of a living
   *     execution, used to detect stale executions
   * @param limit the maximum number of runs to add from this location
   * @throws HopException if the executions of the location can't be listed
   */
  public void loadRuns(
      String locationName,
      IExecutionInfoLocation location,
      IExecutionSelector selector,
      long loggingInterval,
      int limit)
      throws HopException {
    int loadedCount = 0;
    for (String id : location.findExecutionIDs(selector)) {
      // Limit to last executions of this location, the global limit is applied once merged
      if (loadedCount >= limit) {
        break;
      }

      ExecutionRun run = loadRun(locationName, location, id, loggingInterval);
      if (run != null) {
        runs.add(run);
        loadedCount++;
      }
    }
  }

  /**
   * Reads one execution of a location.
   *
   * @return the run, or {@code null} if the execution belongs to another pipeline or workflow, has
   *     no state or can't be read
   */
  private @Nullable ExecutionRun loadRun(
      String locationName, IExecutionInfoLocation location, String id, long loggingInterval) {
    try {
      Execution execution = location.getExecution(id);
      if (execution == null || !name.equals(execution.getName())) {
        return null;
      }

      // Don't load execution logging since that can be a lot of data
      ExecutionState state = location.getExecutionState(id, false);
      if (state == null) {
        return null;
      }

      ExecutionStatus status = ExecutionStatus.from(state, loggingInterval);
      return new ExecutionRun(execution, status, state, locationName, location);
    } catch (Exception e) {
      LogChannel.UI.logError(
          "Unable to load execution " + id + " from location " + locationName, e);
      return null;
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
