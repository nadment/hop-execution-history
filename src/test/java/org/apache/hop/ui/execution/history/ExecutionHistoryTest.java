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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Date;
import java.util.List;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionType;
import org.junit.jupiter.api.Test;

class ExecutionHistoryTest {

  /** Creates a finished run registered at the given time, or without registration date. */
  private static ExecutionRun run(String id, Long registrationTime) {
    Execution execution = new Execution();
    execution.setId(id);
    execution.setRegistrationDate(registrationTime == null ? null : new Date(registrationTime));
    execution.setExecutionStartDate(new Date(0L));
    ExecutionState state = new ExecutionState();
    state.setExecutionEndDate(new Date(1000L));
    return new ExecutionRun(execution, ExecutionStatus.FINISHED, state, "location", null);
  }

  private static List<String> ids(ExecutionHistory history) {
    return history.getRuns().stream().map(ExecutionRun::getId).toList();
  }

  @Test
  void sortAndTruncateOrdersMostRecentFirstWithMissingDatesLast() {
    ExecutionHistory history = new ExecutionHistory(ExecutionType.Pipeline, "test");
    history.getRuns().add(run("old", 1L));
    history.getRuns().add(run("none", null));
    history.getRuns().add(run("new", 3L));

    history.sortAndTruncate(ExecutionHistory.RUN_LIMIT);

    assertEquals(List.of("new", "old", "none"), ids(history));
  }

  @Test
  void sortAndTruncateKeepsMostRecentRunsOfAllLocations() {
    ExecutionHistory history = new ExecutionHistory(ExecutionType.Workflow, "test");
    // Simulates two locations, each contributing RUN_LIMIT runs, interleaved in time
    for (long i = 0; i < 2L * ExecutionHistory.RUN_LIMIT; i++) {
      history.getRuns().add(run(String.valueOf(i), i));
    }

    history.sortAndTruncate(ExecutionHistory.RUN_LIMIT);

    assertEquals(ExecutionHistory.RUN_LIMIT, history.getRuns().size());
    assertEquals(String.valueOf(2L * ExecutionHistory.RUN_LIMIT - 1), history.getRun(0).getId());
    assertEquals(
        String.valueOf(ExecutionHistory.RUN_LIMIT),
        history.getRun(ExecutionHistory.RUN_LIMIT - 1).getId());
  }
}
