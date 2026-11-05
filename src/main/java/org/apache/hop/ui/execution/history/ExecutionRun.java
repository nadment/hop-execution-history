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

import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Snapshot of one pipeline or workflow execution, read from an execution information location.
 *
 * <p>It is rendered as one column of the {@link ExecutionHistoryChart}. The {@code
 * getComponentStatus()} map is filled afterwards by the delegates with the status of each transform
 * or action of the execution.
 */
@NullMarked
@Getter
public class ExecutionRun {

  /** Execution ID, unique within its location. */
  private final String id;

  /** ID of the parent execution, or {@code null} for a top-level execution. */
  private final String parentId;

  /** Name of the executed pipeline or workflow. */
  private final String name;

  private final String runConfigurationName;
  private final ExecutionType executionType;

  /** Status derived from {@link #executionState} when the run was loaded. */
  private final ExecutionStatus executionStatus;

  /** Raw state as read from the location, without logging text. */
  private final ExecutionState executionState;

  private final Date executionStartDate;

  /** Date the execution was registered in the location, used to sort runs. */
  private final Date registrationDate;

  /** Duration in seconds */
  private final long duration;

  /** The name of execution info location metadata */
  private final String locationName;

  /** Initialized location the run was read from, used to open or delete the execution. */
  private final IExecutionInfoLocation location;

  /** Status of each transform or action, keyed by component name. Mutable. */
  private final Map<String, ExecutionStatus> componentStatus;

  /**
   * Creates a run and computes its duration according to its status: up to the end date when
   * completed, up to now when running, up to the last state update when paused or stale.
   *
   * @param execution the execution metadata
   * @param status the status derived from {@code state}
   * @param state the execution state
   * @param locationName the name of the execution information location metadata
   * @param location the initialized execution information location
   */
  public ExecutionRun(
      Execution execution,
      ExecutionStatus status,
      ExecutionState state,
      String locationName,
      IExecutionInfoLocation location) {

    this.id = execution.getId();
    this.name = execution.getName();
    this.parentId = execution.getParentId();
    this.runConfigurationName = execution.getRunConfigurationName();
    this.registrationDate = execution.getRegistrationDate();
    this.executionType = execution.getExecutionType();
    this.executionStartDate = execution.getExecutionStartDate();
    this.executionStatus = status;
    this.executionState = state;
    this.locationName = locationName;
    this.location = location;

    Date startDate = execution.getExecutionStartDate();
    this.duration =
        switch (status) {
          case FINISHED, FAILED, STOPPED -> secondsBetween(startDate, state.getExecutionEndDate());
            // If running execution, use current time to compute duration
          case RUNNING -> secondsBetween(startDate, new Date());
            // If stale execution, use last state update time to compute duration
          case PAUSED, STALE -> secondsBetween(startDate, state.getUpdateTime());
          case UNKNOWN -> 0L;
        };

    this.componentStatus = new LinkedHashMap<>();
  }

  /**
   * Computes the number of seconds between two dates, tolerating dates missing from the location.
   *
   * @param start the start date, may be null
   * @param end the end date, may be null
   * @return the duration in seconds, or 0 if a date is missing
   */
  static long secondsBetween(@Nullable Date start, @Nullable Date end) {
    if (start == null || end == null) {
      return 0L;
    }
    return ChronoUnit.SECONDS.between(start.toInstant(), end.toInstant());
  }
}
