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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.hop.core.Const;
import org.apache.hop.core.Props;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.core.gui.plugin.key.GuiKeyboardShortcut;
import org.apache.hop.core.gui.plugin.key.GuiOsxKeyboardShortcut;
import org.apache.hop.core.gui.plugin.tab.GuiTab;
import org.apache.hop.core.gui.plugin.toolbar.GuiToolbarElement;
import org.apache.hop.core.logging.LogChannel;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowBuffer;
import org.apache.hop.execution.DefaultExecutionSelector;
import org.apache.hop.execution.ExecutionData;
import org.apache.hop.execution.ExecutionDataBuilder;
import org.apache.hop.execution.ExecutionInfoLocation;
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

/**
 * Adds an "Executions" tab to the workflow graph, showing an {@link ExecutionHistoryChart} of the
 * most recent executions of the workflow found in all execution information locations, with the
 * status of each action.
 */
@GuiPlugin(
    name = "Workflow execution history",
    description = "Show the history of recent workflow executions")
public class WorkflowExecutionHistoryDelegate {

  public static final Class<?> PKG = WorkflowExecutionHistoryDelegate.class; // i18n

  public static final String GUI_PLUGIN_TOOLBAR_PARENT_ID = "WorkflowExecutionHistory-Toolbar";
  public static final String TOOLBAR_ITEM_REFRESH =
      "WorkflowExecutionHistory-Toolbar-10100-Refresh";
  public static final String TOOLBAR_ITEM_GET_MORE =
      "WorkflowExecutionHistory-Toolbar-10200-GetMore";
  public static final String TOOLBAR_ITEM_GET_ALL = "WorkflowExecutionHistory-Toolbar-10300-GetAll";

  private final HopGui hopGui;
  private final HopGuiWorkflowGraph workflowGraph;

  /** Locations initialized by the last refresh, keyed by name; closed at the next refresh. */
  private final Map<String, IExecutionInfoLocation> locationMap;

  /**
   * Maximum number of runs to load, raised by {@link #getMoreHistory()} and {@link
   * #getAllHistory()}, and kept by {@link #refresh()}.
   */
  private int runLimit = ExecutionHistory.RUN_PAGE_SIZE;

  private GuiToolbarWidgets toolBarWidgets;
  private ExecutionHistoryChart wChart;

  /**
   * Creates the delegate; instantiated by the GUI plugin framework for each workflow graph.
   *
   * @param hopGui the Hop GUI
   * @param workflowGraph the workflow graph owning the tab
   */
  public WorkflowExecutionHistoryDelegate(HopGui hopGui, HopGuiWorkflowGraph workflowGraph) {
    super();
    this.hopGui = hopGui;
    this.workflowGraph = workflowGraph;
    this.locationMap = new HashMap<>();
  }

  /**
   * Creates the execution history tab with its toolbar and chart. The history is loaded by {@link
   * #refresh()}.
   *
   * @param tabFolder the workflow graph tab folder
   * @return the created tab
   */
  @GuiTab(
      id = "90000-workflow-execution-history-tab",
      parentId = HopGuiWorkflowGraph.WORKFLOW_GRAPH_TABS,
      description = "Workflow execution history")
  public CTabItem addExecutionHistoryTab(CTabFolder tabFolder) {
    CTabItem tab = new CTabItem(tabFolder, SWT.NONE);
    tab.setFont(GuiResource.getInstance().getFontDefault());
    // TODO: use GuiResource.getInstance().getImagePulse() once Hop provides it
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

    // Release the locations when the workflow graph is closed
    composite.addListener(SWT.Dispose, e -> closeLocations());

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

    // Create the chart
    wChart = new ExecutionHistoryChart(composite, SWT.NONE);
    wChart.setLayoutData(new FormDataBuilder().fullWidth().top(toolBar).bottom().result());

    return tab;
  }

  /**
   * Refreshes the workflow execution history by loading the most recent execution runs from all
   * configured execution information locations.
   */
  @GuiToolbarElement(
      root = GUI_PLUGIN_TOOLBAR_PARENT_ID,
      id = TOOLBAR_ITEM_REFRESH,
      toolTip = "i18n::System.Button.Refresh",
      image = "ui/images/refresh.svg")
  @GuiKeyboardShortcut(key = SWT.F5)
  @GuiOsxKeyboardShortcut(key = SWT.F5)
  public void refresh() {
    String workflowName = workflowGraph.getName();
    if (workflowName == null) {
      return;
    }

    Shell shell = hopGui.getShell();

    try {
      shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_WAIT));

      // If there are any cached locations we want to close them before initializing new ones
      closeLocations();

      IHopMetadataProvider metadataProvider = hopGui.getMetadataProvider();
      IHopMetadataSerializer<ExecutionInfoLocation> serializer =
          metadataProvider.getSerializer(ExecutionInfoLocation.class);
      List<ExecutionInfoLocation> locations = serializer.loadAll();

      ExecutionHistory history = new ExecutionHistory(ExecutionType.Workflow, workflowName);
      IExecutionSelector selector =
          new DefaultExecutionSelector(
              false, false, false, false, true, false, workflowName, LastPeriod.NONE);
      for (ExecutionInfoLocation locationMeta : locations) {
        loadLocation(history, locationMeta, selector);
      }

      // Sort by registration date desc and keep the most recent runs of all locations
      history.sortAndTruncate(runLimit);

      loadActionState(history);

      wChart.setExecutionHistory(history);

      // Fewer runs than the limit means the whole history is loaded
      boolean hasMoreHistory = history.getRuns().size() >= runLimit;
      toolBarWidgets.enableToolbarItem(TOOLBAR_ITEM_GET_MORE, hasMoreHistory);
      toolBarWidgets.enableToolbarItem(TOOLBAR_ITEM_GET_ALL, hasMoreHistory);
    } catch (Exception e) {
      LogChannel.UI.logError("Error refreshing workflow execution history", e);
    } finally {
      shell.setCursor(null);
    }
  }

  /**
   * Adds the runs of one location to the history. A failing location is logged, so that the other
   * locations are still loaded.
   */
  private void loadLocation(
      ExecutionHistory history, ExecutionInfoLocation locationMeta, IExecutionSelector selector) {
    try {
      IExecutionInfoLocation location = locationMeta.getExecutionInfoLocation();
      location.initialize(hopGui.getVariables(), hopGui.getMetadataProvider());

      // Keep the location around to close at the next refresh.
      locationMap.put(locationMeta.getName(), location);

      // Logging interval used to determine whether an execution is stale
      long loggingInterval = 2 * Const.toLong(locationMeta.getDataLoggingInterval(), 20000);

      history.loadRuns(locationMeta.getName(), location, selector, loggingInterval, runLimit);
    } catch (Exception e) {
      LogChannel.UI.logError(
          "Unable to retrieve execution information from location " + locationMeta.getName(), e);
    }
  }

  /** Loads {@link ExecutionHistory#RUN_PAGE_SIZE} more runs than currently displayed. */
  @GuiToolbarElement(
      root = GUI_PLUGIN_TOOLBAR_PARENT_ID,
      id = TOOLBAR_ITEM_GET_MORE,
      toolTip = "i18n::ExecutionHistory.Toolbar.GetMoreHistory.Tooltip",
      image = "history-get-more.svg")
  public void getMoreHistory() {
    runLimit = (int) Math.min(Integer.MAX_VALUE, (long) runLimit + ExecutionHistory.RUN_PAGE_SIZE);
    refresh();
  }

  /** Loads all the runs available in the locations. */
  @GuiToolbarElement(
      root = GUI_PLUGIN_TOOLBAR_PARENT_ID,
      id = TOOLBAR_ITEM_GET_ALL,
      toolTip = "i18n::ExecutionHistory.Toolbar.GetAllHistory.Tooltip",
      image = "history-get-all.svg")
  public void getAllHistory() {
    runLimit = Integer.MAX_VALUE;
    refresh();
  }

  /**
   * Closes the locations opened by the last refresh. A failure is logged so that it doesn't prevent
   * closing the other locations.
   */
  private void closeLocations() {
    for (Map.Entry<String, IExecutionInfoLocation> entry : locationMap.entrySet()) {
      try {
        entry.getValue().close();
      } catch (Exception e) {
        LogChannel.UI.logError(
            "Unable to close execution information location " + entry.getKey(), e);
      }
    }
    locationMap.clear();
  }

  /**
   * Loads the status of every action of each run into {@code ExecutionRun.getComponentStatus()},
   * then sorts the component names alphabetically.
   *
   * <p>Actions have no execution state of their own, so the status is derived from the action
   * execution data: finished or not, then the {@code errors} and {@code stopped} result values.
   *
   * @param history the history whose runs are completed
   * @throws HopException if a child execution can't be read from its location
   */
  public void loadActionState(ExecutionHistory history) throws HopException {
    try {
      for (ExecutionRun run : history.getRuns()) {

        // List all action execution states
        List<String> childIds = run.getLocation().findChildIds(ExecutionType.Workflow, run.getId());
        for (String childId : childIds) {
          loadActionState(history, run, childId);
        }
      }

      history.getComponentNames().sort(Comparator.naturalOrder());
    } catch (Exception e) {
      throw new HopException("Error getting child executions", e);
    }
  }

  /** Registers one action of a run and stores its status, if the action has execution data. */
  private static void loadActionState(ExecutionHistory history, ExecutionRun run, String childId)
      throws HopException {
    // Actions don't have an execution state of their own (getExecutionState returns null),
    // so create the status based on the execution data
    ExecutionData executionData = run.getLocation().getExecutionData(run.getId(), childId);
    if (executionData == null || executionData.getDataSetMeta() == null) {
      return;
    }

    String actionName = executionData.getDataSetMeta().getName();
    history.addComponentIfAbsent(actionName);
    run.getComponentStatus()
        .put(actionName, getActionStatus(run.getExecutionStatus(), executionData));
  }

  /**
   * Derives the status of an action from its execution data: finished or not, then the {@code
   * errors} and {@code stopped} result values.
   *
   * @param runStatus the status of the workflow run; an unfinished action of a stale run is stale
   * @param executionData the execution data of the action
   * @return the action status
   */
  static ExecutionStatus getActionStatus(ExecutionStatus runStatus, ExecutionData executionData) {
    ExecutionStatus status = ExecutionStatus.RUNNING;
    if (executionData.isFinished()) {
      status = ExecutionStatus.FINISHED;
    } else if (runStatus == ExecutionStatus.STALE) {
      status = ExecutionStatus.STALE;
    }

    RowBuffer rowBuffer = executionData.getDataSets().get(ExecutionDataBuilder.KEY_RESULT);
    if (rowBuffer == null || rowBuffer.getRowMeta() == null) {
      return status;
    }

    for (Object[] row : rowBuffer.getBuffer()) {
      status = getResultStatus(rowBuffer.getRowMeta(), row, status);
    }
    return status;
  }

  /**
   * Applies one result row of an action: errors make it failed, a stop makes it stopped.
   *
   * @return the new status, or {@code status} if the row doesn't change it or can't be read
   */
  private static ExecutionStatus getResultStatus(
      IRowMeta rowMeta, Object[] row, ExecutionStatus status) {
    try {
      String key = rowMeta.getString(row, 0);
      if (ExecutionDataBuilder.RESULT_KEY_ERRORS.equals(key)) {
        return Long.parseLong(rowMeta.getString(row, 1)) > 0 ? ExecutionStatus.FAILED : status;
      }
      if (ExecutionDataBuilder.RESULT_KEY_STOPPED.equals(key)) {
        return "true".equalsIgnoreCase(rowMeta.getString(row, 1))
            ? ExecutionStatus.STOPPED
            : status;
      }
    } catch (Exception e) {
      LogChannel.UI.logError("Error getting action result information", e);
    }
    return status;
  }
}
