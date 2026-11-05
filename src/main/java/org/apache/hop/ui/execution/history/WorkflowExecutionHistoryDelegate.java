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
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Adds an "Executions" tab to the workflow graph, showing an {@link ExecutionHistoryChart} of the
 * most recent executions of the workflow found in all execution information locations, with the
 * status of each action.
 */
@NullMarked
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

  /**
   * Locations used by the displayed runs, keyed by name; closed when a refresh replaces them or
   * when the workflow graph is closed.
   */
  private final Map<String, IExecutionInfoLocation> locationMap;

  /**
   * Maximum number of runs to load, raised by {@link #getMoreHistory()} and {@link
   * #getAllHistory()}, and kept by {@link #refresh()}.
   */
  private int runLimit = ExecutionHistory.RUN_PAGE_SIZE;

  /** Whether a refresh was ever started; the first display of the tab starts one. */
  private boolean refreshed;

  /** Whether a refresh is running in the background. Only used in the UI thread. */
  private boolean refreshing;

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
   * #refresh()}, started automatically the first time the tab is shown.
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
    composite.addListener(SWT.Dispose, e -> closeLocations(locationMap));

    // Load the history the first time the tab is shown, whether selected by the user or not
    composite.addListener(
        SWT.Show,
        e -> {
          if (!refreshed) {
            refresh();
          }
        });

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
   *
   * <p>The runs are loaded in a background thread, so that Hop GUI stays responsive, and then
   * displayed in the UI thread. The toolbar is disabled meanwhile, and a refresh requested while
   * one is running is ignored.
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
    if (workflowName == null || refreshing) {
      return;
    }
    refreshed = true;
    refreshing = true;
    enableToolbar(false, false);

    // A dedicated thread, unlike a shared pool, inherits the context class loader of the UI thread
    int limit = runLimit;
    Display display = wChart.getDisplay();
    Thread thread =
        new Thread(
            () -> {
              Map<String, IExecutionInfoLocation> locations = new HashMap<>();
              ExecutionHistory history = loadHistory(workflowName, limit, locations);
              showHistoryLater(display, history, locations, limit);
            },
            "Workflow execution history refresh");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Loads the history from all the locations. Runs in the background thread, so it must not touch
   * the widgets nor the fields shared with the UI thread.
   *
   * @param workflowName the name of the workflow
   * @param limit the maximum number of runs to load
   * @param locations receives the initialized locations, to close once no longer used
   * @return the history, or {@code null} if it can't be loaded
   */
  private @Nullable ExecutionHistory loadHistory(
      String workflowName, int limit, Map<String, IExecutionInfoLocation> locations) {
    try {
      IHopMetadataProvider metadataProvider = hopGui.getMetadataProvider();
      IHopMetadataSerializer<ExecutionInfoLocation> serializer =
          metadataProvider.getSerializer(ExecutionInfoLocation.class);

      ExecutionHistory history = new ExecutionHistory(ExecutionType.Workflow, workflowName);
      IExecutionSelector selector =
          new DefaultExecutionSelector(
              false, false, false, false, true, false, workflowName, LastPeriod.NONE);
      for (ExecutionInfoLocation locationMeta : serializer.loadAll()) {
        loadLocation(history, locationMeta, selector, limit, locations);
      }

      // Sort by registration date desc and keep the most recent runs of all locations
      history.sortAndTruncate(limit);

      loadActionState(history);
      return history;
    } catch (Exception e) {
      LogChannel.UI.logError("Error refreshing workflow execution history", e);
      return null;
    }
  }

  /**
   * Adds the runs of one location to the history. A failing location is logged, so that the other
   * locations are still loaded.
   */
  private void loadLocation(
      ExecutionHistory history,
      ExecutionInfoLocation locationMeta,
      IExecutionSelector selector,
      int limit,
      Map<String, IExecutionInfoLocation> locations) {
    try {
      IExecutionInfoLocation location = locationMeta.getExecutionInfoLocation();
      location.initialize(hopGui.getVariables(), hopGui.getMetadataProvider());

      // Keep the location around, the displayed runs use it to open or delete their execution
      locations.put(locationMeta.getName(), location);

      // Logging interval used to determine whether an execution is stale
      long loggingInterval = 2 * Const.toLong(locationMeta.getDataLoggingInterval(), 20000);

      history.loadRuns(locationMeta.getName(), location, selector, loggingInterval, limit);
    } catch (Exception e) {
      LogChannel.UI.logError(
          "Unable to retrieve execution information from location " + locationMeta.getName(), e);
    }
  }

  /**
   * Hands the loaded history over to the UI thread, or closes its locations if Hop GUI is already
   * closed. Called from the background thread.
   */
  private void showHistoryLater(
      Display display,
      @Nullable ExecutionHistory history,
      Map<String, IExecutionInfoLocation> locations,
      int limit) {
    try {
      display.asyncExec(() -> showHistory(history, locations, limit));
    } catch (SWTException e) {
      // The display is disposed, nobody will use these locations
      closeLocations(locations);
    }
  }

  /**
   * Displays the loaded history and keeps its locations, closing the previous ones. Runs in the UI
   * thread.
   *
   * @param history the loaded history, or {@code null} if the refresh failed
   * @param locations the locations used by the loaded runs
   * @param limit the maximum number of runs that was requested
   */
  private void showHistory(
      @Nullable ExecutionHistory history,
      Map<String, IExecutionInfoLocation> locations,
      int limit) {
    refreshing = false;

    // The workflow graph was closed during the refresh
    if (wChart.isDisposed()) {
      closeLocations(locations);
      return;
    }

    // The refresh failed: keep the displayed history and allow to retry
    if (history == null) {
      closeLocations(locations);
      enableToolbar(true, true);
      return;
    }

    // The displayed runs now use the new locations, the previous ones are no longer used
    closeLocations(locationMap);
    locationMap.putAll(locations);
    wChart.setExecutionHistory(history);

    // Fewer runs than the limit means the whole history is loaded
    enableToolbar(true, history.getRuns().size() >= limit);
  }

  /** Enables the toolbar buttons: refresh, and get more or all history. */
  private void enableToolbar(boolean canRefresh, boolean hasMoreHistory) {
    toolBarWidgets.enableToolbarItem(TOOLBAR_ITEM_REFRESH, canRefresh);
    toolBarWidgets.enableToolbarItem(TOOLBAR_ITEM_GET_MORE, hasMoreHistory);
    toolBarWidgets.enableToolbarItem(TOOLBAR_ITEM_GET_ALL, hasMoreHistory);
  }

  /** Loads {@link ExecutionHistory#RUN_PAGE_SIZE} more runs than currently displayed. */
  @GuiToolbarElement(
      root = GUI_PLUGIN_TOOLBAR_PARENT_ID,
      id = TOOLBAR_ITEM_GET_MORE,
      toolTip = "i18n::ExecutionHistory.Toolbar.GetMoreHistory.Tooltip",
      image = "history-get-more.svg")
  public void getMoreHistory() {
    if (refreshing) {
      return;
    }
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
    if (refreshing) {
      return;
    }
    runLimit = Integer.MAX_VALUE;
    refresh();
  }

  /**
   * Closes locations and empties the map. A failure is logged so that it doesn't prevent closing
   * the other locations.
   *
   * @param locations the locations to close, keyed by name
   */
  private static void closeLocations(Map<String, IExecutionInfoLocation> locations) {
    for (Map.Entry<String, IExecutionInfoLocation> entry : locations.entrySet()) {
      try {
        entry.getValue().close();
      } catch (Exception e) {
        LogChannel.UI.logError(
            "Unable to close execution information location " + entry.getKey(), e);
      }
    }
    locations.clear();
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
