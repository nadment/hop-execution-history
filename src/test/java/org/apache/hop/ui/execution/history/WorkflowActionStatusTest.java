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

import static org.apache.hop.ui.execution.history.WorkflowExecutionHistoryDelegate.getActionStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowBuffer;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaString;
import org.apache.hop.execution.ExecutionData;
import org.apache.hop.execution.ExecutionDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests the status of a workflow action derived from its execution data. */
class WorkflowActionStatusTest {

  /** Unreadable result rows are logged, which needs the Hop log store. */
  @BeforeAll
  static void initLogStore() {
    HopLogStore.init();
  }

  /** Creates the execution data of an action, with the given result rows (key, value). */
  private static ExecutionData actionData(boolean finished, String... keyValues) {
    IRowMeta rowMeta = new RowMeta();
    rowMeta.addValueMeta(new ValueMetaString("key"));
    rowMeta.addValueMeta(new ValueMetaString("value"));
    List<Object[]> rows = new ArrayList<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      rows.add(new Object[] {keyValues[i], keyValues[i + 1]});
    }

    Map<String, RowBuffer> dataSets = new HashMap<>();
    dataSets.put(ExecutionDataBuilder.KEY_RESULT, new RowBuffer(rowMeta, rows));

    ExecutionData data = new ExecutionData();
    data.setFinished(finished);
    data.setDataSets(dataSets);
    return data;
  }

  @Test
  void finishedActionWithoutErrorIsFinished() {
    assertEquals(
        ExecutionStatus.FINISHED,
        getActionStatus(
            ExecutionStatus.FINISHED,
            actionData(true, ExecutionDataBuilder.RESULT_KEY_ERRORS, "0")));
  }

  @Test
  void unfinishedActionOfRunningRunIsRunning() {
    assertEquals(
        ExecutionStatus.RUNNING, getActionStatus(ExecutionStatus.RUNNING, actionData(false)));
  }

  @Test
  void unfinishedActionOfStaleRunIsStale() {
    assertEquals(ExecutionStatus.STALE, getActionStatus(ExecutionStatus.STALE, actionData(false)));
  }

  @Test
  void actionWithErrorsIsFailed() {
    assertEquals(
        ExecutionStatus.FAILED,
        getActionStatus(
            ExecutionStatus.FAILED, actionData(true, ExecutionDataBuilder.RESULT_KEY_ERRORS, "2")));
  }

  @Test
  void stoppedActionIsStopped() {
    assertEquals(
        ExecutionStatus.STOPPED,
        getActionStatus(
            ExecutionStatus.STOPPED,
            actionData(true, ExecutionDataBuilder.RESULT_KEY_STOPPED, "true")));
  }

  @Test
  void unreadableResultRowKeepsCurrentStatus() {
    assertEquals(
        ExecutionStatus.FINISHED,
        getActionStatus(
            ExecutionStatus.FINISHED,
            actionData(true, ExecutionDataBuilder.RESULT_KEY_ERRORS, "not a number")));
  }

  @Test
  void actionWithoutResultKeepsFinishedStatus() {
    ExecutionData data = new ExecutionData();
    data.setFinished(true);
    data.setDataSets(new HashMap<>());
    assertEquals(ExecutionStatus.FINISHED, getActionStatus(ExecutionStatus.FINISHED, data));
  }
}
