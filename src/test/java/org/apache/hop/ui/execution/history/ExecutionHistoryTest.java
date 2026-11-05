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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.apache.hop.execution.IExecutionSelector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ExecutionHistoryTest {

  /** Unreadable executions are logged, which needs the Hop log store. */
  @BeforeAll
  static void initLogStore() {
    HopLogStore.init();
  }

  /** Simulates a location listing the given execution IDs, most recent first. */
  private static IExecutionInfoLocation location(String... ids) throws HopException {
    IExecutionInfoLocation location = mock(IExecutionInfoLocation.class);
    when(location.findExecutionIDs(any())).thenReturn(List.of(ids));
    return location;
  }

  /** Makes the location return a finished execution of the given pipeline. */
  private static void addExecution(IExecutionInfoLocation location, String id, String name)
      throws HopException {
    Execution execution = new Execution();
    execution.setId(id);
    execution.setName(name);
    when(location.getExecution(id)).thenReturn(execution);
    ExecutionState state = new ExecutionState();
    state.setExecutionEndDate(new Date());
    when(location.getExecutionState(id, false)).thenReturn(state);
  }

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
  void loadRunsSkipsForeignMissingAndUnreadableExecutions() throws HopException {
    IExecutionInfoLocation location = location("other", "noState", "broken", "ok");
    addExecution(location, "other", "another pipeline");
    Execution withoutState = new Execution();
    withoutState.setName("test");
    when(location.getExecution("noState")).thenReturn(withoutState);
    when(location.getExecution("broken")).thenThrow(new HopException("unreadable"));
    addExecution(location, "ok", "test");

    ExecutionHistory history = new ExecutionHistory(ExecutionType.Pipeline, "test");
    history.loadRuns("location", location, mock(IExecutionSelector.class), 20000, 10);

    assertEquals(List.of("ok"), ids(history));
  }

  @Test
  void loadRunsStopsReadingAtTheLimit() throws HopException {
    IExecutionInfoLocation location = location("1", "2", "3");
    addExecution(location, "1", "test");
    addExecution(location, "2", "test");
    addExecution(location, "3", "test");

    ExecutionHistory history = new ExecutionHistory(ExecutionType.Workflow, "test");
    history.loadRuns("location", location, mock(IExecutionSelector.class), 20000, 2);

    assertEquals(List.of("1", "2"), ids(history));
    verify(location, never()).getExecution("3");
  }

  @Test
  void sortAndTruncateOrdersMostRecentFirstWithMissingDatesLast() {
    ExecutionHistory history = new ExecutionHistory(ExecutionType.Pipeline, "test");
    history.getRuns().add(run("old", 1L));
    history.getRuns().add(run("none", null));
    history.getRuns().add(run("new", 3L));

    history.sortAndTruncate(ExecutionHistory.RUN_PAGE_SIZE);

    assertEquals(List.of("new", "old", "none"), ids(history));
  }

  @Test
  void sortAndTruncateKeepsMostRecentRunsOfAllLocations() {
    ExecutionHistory history = new ExecutionHistory(ExecutionType.Workflow, "test");
    // Simulates two locations, each contributing RUN_PAGE_SIZE runs, interleaved in time
    for (long i = 0; i < 2L * ExecutionHistory.RUN_PAGE_SIZE; i++) {
      history.getRuns().add(run(String.valueOf(i), i));
    }

    history.sortAndTruncate(ExecutionHistory.RUN_PAGE_SIZE);

    assertEquals(ExecutionHistory.RUN_PAGE_SIZE, history.getRuns().size());
    assertEquals(
        String.valueOf(2L * ExecutionHistory.RUN_PAGE_SIZE - 1), history.getRun(0).getId());
    assertEquals(
        String.valueOf(ExecutionHistory.RUN_PAGE_SIZE),
        history.getRun(ExecutionHistory.RUN_PAGE_SIZE - 1).getId());
  }
}
