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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;

@Getter
public class ExecutionRun {
  private final ExecutionState executionState;

  private final String id;
  private final String parentId;
  private final String name;
  private final String runConfigurationName;
  private final Date registrationDate;
  private final Date executionStartDate;
  private final ExecutionType executionType;

  /** Duration in seconds */
  private final long duration;

  /** The name of execution info location metadata */
  private final String locationName;

  private final IExecutionInfoLocation location;
  private final Map<String, ExecutionState> componentStates;

  public ExecutionRun(
      Execution execution,
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
    this.executionState = state;

    // If running execution, use current time to compute duration
    Instant end =
        (state.getExecutionEndDate() != null)
            ? state.getExecutionEndDate().toInstant()
            : Instant.now();

    this.duration = ChronoUnit.SECONDS.between(execution.getExecutionStartDate().toInstant(), end);
    this.locationName = locationName;
    this.location = location;
    this.componentStates = new LinkedHashMap<>();
  }
}
