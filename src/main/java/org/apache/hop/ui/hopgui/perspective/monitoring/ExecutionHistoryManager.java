/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hop.ui.hopgui.perspective.monitoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowBuffer;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionData;
import org.apache.hop.execution.ExecutionDataBuilder;
import org.apache.hop.execution.ExecutionDataSetMeta;
import org.apache.hop.execution.ExecutionInfoLocation;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataSerializer;
import org.apache.hop.ui.execution.history.ExecutionHistory;
import org.apache.hop.ui.execution.history.ExecutionRun;
import org.apache.hop.ui.hopgui.HopGui;

public class ExecutionHistoryManager {

  /** Maximum number of recent runs (columns) shown per workflow. */
  public static final int MAX_RUNS = 10;

  /** Maximum number of executions scanned to build the execution list and group runs. */
  private static final int SCAN_LIMIT = 5000;

  /** All loaded executions grouped by workflow. */
  private final Map<String, ExecutionHistory> histories = new TreeMap<>();

  private HopGui hopGui;
  private Map<String, ExecutionInfoLocation> locationMap;

  public ExecutionHistoryManager(HopGui hopGui) {
    super();
    this.hopGui = hopGui;
  }

  public void load() {
    try {

      // If there are any cached locations, we want to close them before initializing new ones
      //
      for (ExecutionInfoLocation location : locationMap.values()) {
        location.getExecutionInfoLocation().close();
      }
      locationMap.clear();

      histories.clear();

      IHopMetadataProvider metadataProvider = hopGui.getMetadataProvider();
      IHopMetadataSerializer<ExecutionInfoLocation> serializer =
          metadataProvider.getSerializer(ExecutionInfoLocation.class);
      List<ExecutionInfoLocation> locations = serializer.loadAll();

      for (ExecutionInfoLocation locationMeta : locations) {
        IExecutionInfoLocation location = locationMeta.getExecutionInfoLocation();
        try {
          // Initialize the location first...
          location.initialize(hopGui.getVariables(), hopGui.getMetadataProvider());

          // Keep the location around to close at the next refresh.
          locationMap.put(locationMeta.getName(), locationMeta);

          List<String> ids = location.getExecutionIds(false, SCAN_LIMIT);
          for (String id : ids) {
            Execution execution = location.getExecution(id);

            // Keep only workflow executions
            if (execution == null || execution.getExecutionType() != ExecutionType.Workflow) {
              continue;
            }

            ExecutionHistory history =
                histories.computeIfAbsent(
                    execution.getName(),
                    k -> new ExecutionHistory(ExecutionType.Workflow, execution.getName()));

            ExecutionState state = location.getExecutionState(id, false);

            history
                .getRuns()
                .add(new ExecutionRun(execution, state, locationMeta.getName(), location));
          }
        } catch (Exception ex) {
          LogChannel.GENERAL.logError(
              "Unable to initialize execution information location " + locationMeta.getName(), ex);
        }
      }

      // Sort each group's runs by registration date desc and truncate to MAX_RUNS.
      for (ExecutionHistory history : histories.values()) {
        history
            .getRuns()
            .sort(
                Comparator.comparing(
                        (ExecutionRun r) ->
                            r.getExecution().getRegistrationDate() == null
                                ? new Date(0)
                                : r.getExecution().getRegistrationDate())
                    .reversed());
        if (history.getRuns().size() > MAX_RUNS) {
          history.getRuns().subList(MAX_RUNS, history.getRuns().size()).clear();
        }

        // Collect the information of all the executed actions across the recent runs.
        LinkedHashMap<String, Integer> actionNames = new LinkedHashMap<>();
        long maxDuration = 0;
        for (ExecutionRun run : history.getRuns()) {
          try {

            // Find the maximum execution duration
            if (maxDuration < run.getDuration()) {
              maxDuration = run.getDuration();
            }

            if ("demo-loop".equals(run.getExecution().getName())) {
              // System.out.println("Workflow: " + run.execution.getName());
            }

            // List all action execution states
            List<String> childIds =
                run.getLocation().findChildIds(ExecutionType.Workflow, run.getExecution().getId());
            if (childIds != null) {
              for (String childId : childIds) {
                ExecutionData executionData =
                    run.getLocation().getExecutionData(run.getExecution().getId(), childId);

                // Action doesn't have state, return null (BUG ?)
                // ExecutionState childState = run.location.getExecutionState(childId);

                // Create execution state based on execution data
                ExecutionDataSetMeta dataSetMeta = executionData.getDataSetMeta();
                if (dataSetMeta != null) {
                  String actionName = dataSetMeta.getName();

                  actionNames.putIfAbsent(actionName, actionNames.size());

                  // Add this one under that name
                  ExecutionState state = new ExecutionState();
                  state.setId(childId);
                  state.setParentId(run.getExecution().getId());
                  state.setName(actionName);
                  state.setExecutionType(ExecutionType.Action);
                  if (executionData.isFinished()) {
                    state.setExecutionEndDate(executionData.getCollectionDate());
                  }

                  RowBuffer rowBuffer =
                      executionData.getDataSets().get(ExecutionDataBuilder.KEY_RESULT);
                  if (rowBuffer != null) {
                    IRowMeta rowMeta = rowBuffer.getRowMeta();
                    if (rowMeta != null) {
                      for (Object[] row : rowBuffer.getBuffer()) {
                        try {
                          if (rowMeta
                              .getString(row, 0)
                              .equals(ExecutionDataBuilder.RESULT_KEY_ERRORS)) {
                            long errors = Long.parseLong(rowMeta.getString(row, 1));
                            state.setFailed(errors > 0);
                          }
                          if (rowMeta
                              .getString(row, 0)
                              .equals(ExecutionDataBuilder.RESULT_KEY_STOPPED)) {
                            // state.setFailed("true".equalsIgnoreCase(rowMeta.getString(row, 1)));
                          }
                        } catch (Exception e) {
                          LogChannel.UI.logError("Error getting action result information", e);
                        }
                      }
                    }
                  }
                  run.getComponentStates().put(actionName, state);
                }
              }
            }
          } catch (Exception ex) {
            LogChannel.GENERAL.logDebug(
                "Could not collect child action for "
                    + run.getExecution().getName()
                    + ": "
                    + ex.getMessage());
          }
        }
        history.setMaxDuration(maxDuration);
        history.setComponentNames(new ArrayList<>(actionNames.keySet()));
      }
    } catch (Exception e) {
      LogChannel.GENERAL.logError("Error refreshing monitoring perspective", e);
    }
  }
}
