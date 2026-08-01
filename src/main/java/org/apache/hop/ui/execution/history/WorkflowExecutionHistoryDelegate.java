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
import java.util.Date;
import java.util.List;
import org.apache.hop.core.Props;
import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.core.gui.plugin.key.GuiKeyboardShortcut;
import org.apache.hop.core.gui.plugin.key.GuiOsxKeyboardShortcut;
import org.apache.hop.core.gui.plugin.tab.GuiTab;
import org.apache.hop.core.gui.plugin.toolbar.GuiToolbarElement;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowBuffer;
import org.apache.hop.execution.DefaultExecutionSelector;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionData;
import org.apache.hop.execution.ExecutionDataBuilder;
import org.apache.hop.execution.ExecutionDataSetMeta;
import org.apache.hop.execution.ExecutionInfoLocation;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.execution.ExecutionType;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.apache.hop.execution.IExecutionSelector;
import org.apache.hop.execution.LastPeriod;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataSerializer;
import org.apache.hop.ui.core.ConstUi;
import org.apache.hop.ui.core.FormDataBuilder;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.gui.GuiResource;
import org.apache.hop.ui.core.gui.GuiToolbarWidgets;
import org.apache.hop.ui.core.gui.IToolbarContainer;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.ToolbarFacade;
import org.apache.hop.ui.hopgui.file.workflow.HopGuiWorkflowGraph;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

@GuiPlugin(
    name = "Workflow execution history",
    description = "Show the history of recent workflow executions")
public class WorkflowExecutionHistoryDelegate {

  public static final Class<?> PKG = WorkflowExecutionHistoryDelegate.class; // i18n

  public static final String GUI_PLUGIN_TOOLBAR_PARENT_ID = "WorkflowExecutionHistory-Toolbar";
  public static final String TOOLBAR_ITEM_REFRESH =
      "WorkflowExecutionHistory-Toolbar-10100-Refresh";

  private HopGui hopGui;
  private HopGuiWorkflowGraph workflowGraph;
  private GuiToolbarWidgets toolBarWidgets;
  private ExecutionHistoryChart wChart;

  public WorkflowExecutionHistoryDelegate(HopGui hopGui, HopGuiWorkflowGraph workflowGraph) {
    super();
    this.hopGui = hopGui;
    this.workflowGraph = workflowGraph;
  }

  @GuiTab(
      id = "90000-workflow-execution-history-tab",
      parentId = HopGuiWorkflowGraph.WORKFLOW_GRAPH_TABS,
      description = "Workflow execution history")
  public CTabItem addExecutionHistoryTab(CTabFolder tabFolder) {
    CTabItem tab = new CTabItem(tabFolder, SWT.NONE);
    tab.setFont(GuiResource.getInstance().getFontDefault());
    // TODO:
    // tab.setImage(GuiResource.getInstance().getImagePulse());
    tab.setImage(
        GuiResource.getInstance()
            .getImage(
                "pulse.svg",
                PKG.getClassLoader(),
                ConstUi.SMALL_ICON_SIZE,
                ConstUi.SMALL_ICON_SIZE));
    tab.setText(BaseMessages.getString(PKG, "ExecutionHistory.Tab.Name"));

    Composite composite = new Composite(tabFolder, SWT.NONE);
    tab.setControl(composite);
    composite.setLayout(new FormLayout());

    // Create toolbar
    IToolbarContainer toolBarContainer =
        ToolbarFacade.createToolbarContainer(composite, SWT.WRAP | SWT.LEFT | SWT.HORIZONTAL);
    toolBarWidgets = new GuiToolbarWidgets();
    toolBarWidgets.registerGuiPluginObject(this);
    toolBarWidgets.createToolbarWidgets(toolBarContainer, GUI_PLUGIN_TOOLBAR_PARENT_ID);
    Control toolBar = toolBarContainer.getControl();
    toolBar.setLayoutData(new FormDataBuilder().fullWidth().top().result());
    toolBar.pack();
    PropsUi.setLook(toolBar, Props.WIDGET_STYLE_TOOLBAR);

    wChart = new ExecutionHistoryChart(composite, SWT.NONE);
    wChart.setLayoutData(new FormDataBuilder().fullWidth().top(toolBar).bottom().result());

    return tab;
  }

  /** Reload all execution information locations and rebuild the execution history. */
  @GuiToolbarElement(
      root = GUI_PLUGIN_TOOLBAR_PARENT_ID,
      id = TOOLBAR_ITEM_REFRESH,
      toolTip = "i18n::ExecutionHistory.Button.Refresh.Tooltip",
      image = "ui/images/refresh.svg")
  @GuiKeyboardShortcut(key = SWT.F5)
  @GuiOsxKeyboardShortcut(key = SWT.F5)
  public void refresh() {
    String workflowName = workflowGraph.getName();
    if (workflowName == null) {
      return;
    }

    Shell shell = hopGui.getShell();
    shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_WAIT));

    try {
      IHopMetadataProvider metadataProvider = hopGui.getMetadataProvider();
      IHopMetadataSerializer<ExecutionInfoLocation> serializer =
          metadataProvider.getSerializer(ExecutionInfoLocation.class);
      List<ExecutionInfoLocation> locations = serializer.loadAll();

      ExecutionHistory history = new ExecutionHistory(ExecutionType.Workflow, workflowName);
      for (ExecutionInfoLocation locationMeta : locations) {
        try {
          IExecutionInfoLocation location = locationMeta.getExecutionInfoLocation();
          location.initialize(hopGui.getVariables(), hopGui.getMetadataProvider());

          IExecutionSelector selector =
              new DefaultExecutionSelector(
                  false, false, false, false, true, false, null, LastPeriod.TWO_MONTHS);

          for (String id : location.findExecutionIDs(selector)) {
            Execution execution = location.getExecution(id);
            if (execution != null && workflowName.equals(execution.getName())) {
              // Don't load execution logging since that can be a lot of data
              ExecutionState state = location.getExecutionState(execution.getId(), false);
              ExecutionRun run =
                  new ExecutionRun(execution, state, locationMeta.getName(), location);
              history.getRuns().add(run);
            }
          }
        } catch (Exception ex) {
          LogChannel.GENERAL.logError(
              "Unable to initialize execution information location " + locationMeta.getName(), ex);
        }
      }

      // Sort by registration date desc and truncate.
      history
          .getRuns()
          .sort(
              Comparator.comparing(
                      (ExecutionRun r) ->
                          r.execution.getRegistrationDate() == null
                              ? new Date(0)
                              : r.execution.getRegistrationDate())
                  .reversed());
      if (history.getRuns().size() > 20) {
        history.getRuns().subList(20, history.getRuns().size()).clear();
      }

      long maxDuration = 0;
      List<String> actionNames = new ArrayList<>();
      for (ExecutionRun run : history.getRuns()) {
        // Find the longest execution time
        if (maxDuration < run.duration) {
          maxDuration = run.duration;
        }

        try {
          // List all action execution states
          List<String> childIds =
              run.location.findChildIds(ExecutionType.Workflow, run.execution.getId());
          for (String childId : childIds) {
            ExecutionData executionData =
                run.location.getExecutionData(run.execution.getId(), childId);

            // Action doesn't have state, return null (BUG ?)
            ExecutionState childState = run.location.getExecutionState(childId, false);

            // Create execution state based on execution data
            ExecutionDataSetMeta dataSetMeta = executionData.getDataSetMeta();
            if (dataSetMeta != null) {
              String actionName = dataSetMeta.getName();

              // actionNames.putIfAbsent(actionName, actionNames.size());
              if (!actionNames.contains(actionName)) {
                actionNames.add(actionName);
              }

              // Add this one under that name
              ExecutionState state = new ExecutionState();
              state.setId(childId);
              state.setParentId(run.execution.getId());
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
              run.componentStates.put(actionName, state);
            }
          }

        } catch (Exception e) {
          LogChannel.GENERAL.logError(
              "Error getting child execution IDs for execution " + run.execution.getId(), e);
        }
      }
      history.setMaxDuration(maxDuration);
      history.setComponentNames(actionNames);

      wChart.setExecutionHistory(history);
    } catch (Exception e) {
      LogChannel.GENERAL.logError("Error refreshing workflow execution history", e);
    } finally {
      shell.setCursor(null);
    }
  }
}
