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
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.IExecutionInfoLocation;

@Getter
public class ExecutionRun {
  final Execution execution;
  final ExecutionState executionState;

  /** Duration in seconds */
  final long duration;

  /** The name of execution info location metadata */
  final String locationName;

  final IExecutionInfoLocation location;
  final Map<String, ExecutionState> componentStates;

  public ExecutionRun(
      Execution execution,
      ExecutionState state,
      String locationName,
      IExecutionInfoLocation location) {
    this.execution = execution;
    this.executionState = state;

    // If running execution, use current time
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
