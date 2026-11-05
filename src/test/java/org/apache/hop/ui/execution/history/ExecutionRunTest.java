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
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExecutionRunTest {

  private static ExecutionRun run(ExecutionStatus status, Date start, Date end, Date update) {
    Execution execution = new Execution();
    execution.setExecutionStartDate(start);
    ExecutionState state = new ExecutionState();
    state.setExecutionEndDate(end);
    state.setUpdateTime(update);
    return new ExecutionRun(execution, status, state, "location", null);
  }

  @Test
  void durationOfFinishedRunUsesEndDate() {
    assertEquals(
        90, run(ExecutionStatus.FINISHED, new Date(0L), new Date(90_000L), null).getDuration());
  }

  @Test
  void durationOfStaleRunUsesLastUpdate() {
    assertEquals(
        30, run(ExecutionStatus.STALE, new Date(0L), null, new Date(30_000L)).getDuration());
  }

  @ParameterizedTest
  @EnumSource(ExecutionStatus.class)
  void missingDatesGiveZeroDurationInsteadOfFailing(ExecutionStatus status) {
    assertEquals(0, run(status, null, null, null).getDuration());
  }
}
