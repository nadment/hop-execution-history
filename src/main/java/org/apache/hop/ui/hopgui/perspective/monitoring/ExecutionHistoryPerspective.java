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

package org.apache.hop.ui.hopgui.perspective.monitoring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.Getter;
import org.apache.hop.core.Props;
import org.apache.hop.core.gui.plugin.GuiPlugin;
import org.apache.hop.core.gui.plugin.key.GuiKeyboardShortcut;
import org.apache.hop.core.gui.plugin.key.GuiOsxKeyboardShortcut;
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
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataSerializer;
import org.apache.hop.ui.core.FormDataBuilder;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.bus.HopGuiEvents;
import org.apache.hop.ui.core.gui.GuiResource;
import org.apache.hop.ui.core.gui.GuiToolbarWidgets;
import org.apache.hop.ui.core.gui.IToolbarContainer;
import org.apache.hop.ui.execution.history.ExecutionHistory;
import org.apache.hop.ui.execution.history.ExecutionHistoryChart;
import org.apache.hop.ui.execution.history.ExecutionRun;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.HopGuiKeyHandler;
import org.apache.hop.ui.hopgui.ToolbarFacade;
import org.apache.hop.ui.hopgui.context.IGuiContextHandler;
import org.apache.hop.ui.hopgui.perspective.HopPerspectivePlugin;
import org.apache.hop.ui.hopgui.perspective.IHopPerspective;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * A perspective for monitoring workflow execution by that displays a grid of recent execution.
 *
 * <p>Cells are colored by the execution executionState (running/failed/finished/unknown) and
 * provide tooltips with execution details on hover.
 */
@HopPerspectivePlugin(
    id = "148-ExecutionHistoryPerspective",
    name = "i18n::ExecutionHistoryPerspective.Name",
    description = "i18n::ExecutionHistoryPerspective.Description",
    image = "monitoring.svg",
    documentationUrl = "/hop-gui/perspective-execution-information.html")
@GuiPlugin(
    name = "i18n::ExecutionHistoryPerspective.Name",
    description = "i18n::ExecutionHistoryPerspective.Description")
public class ExecutionHistoryPerspective implements IHopPerspective {

  public static final Class<?> PKG = ExecutionHistoryPerspective.class; // i18n

  public static final String GUI_PLUGIN_TOOLBAR_PARENT_ID = "ExecutionHistoryPerspective-Toolbar";
  public static final String TOOLBAR_ITEM_REFRESH =
      "ExecutionHistoryPerspective-Toolbar-10100-Refresh";

  /** Maximum number of executions scanned to build the execution list and group runs. */
  private static final int SCAN_LIMIT = 5000;

  @Getter private static ExecutionHistoryPerspective instance;

  private HopGui hopGui;
  private SashForm wSash;
  private Table wTable;
  private ExecutionHistoryChart wExecutionHistoryChart;
  private GuiToolbarWidgets toolBarWidgets;

  /** All loaded executions history grouped by workflow. */
  private final Map<String, ExecutionHistory> executionHistories = new TreeMap<>();

  /** Currently displayed workflow history (null = none). */
  private ExecutionHistory currentExecutionHistory;

  /** Currently selected workflow execution (null = none). */
  private ExecutionRun currentExecution;

  private Map<String, ExecutionInfoLocation> locationMap;

  public ExecutionHistoryPerspective() {
    instance = this;
  }

  @Override
  public String getId() {
    return "monitor-perspective";
  }

  @Override
  public void activate() {
    if (hopGui != null) {
      hopGui.setActivePerspective(this);
    }
  }

  @Override
  public void perspectiveActivated() {
    refresh();
  }

  @Override
  public boolean isActive() {
    return hopGui != null && hopGui.isActivePerspective(this);
  }

  @Override
  public Control getControl() {
    return wSash;
  }

  protected Shell getShell() {
    return hopGui.getShell();
  }

  @Override
  public List<IGuiContextHandler> getContextHandlers() {
    return List.of();
  }

  @Override
  public void initialize(HopGui hopGui, Composite parent) {
    this.hopGui = hopGui;
    this.locationMap = new HashMap<>();

    wSash = new SashForm(parent, SWT.HORIZONTAL);
    wSash.setLayoutData(new FormDataBuilder().fullSize());

    // Left side: list of workflows
    wTable = new Table(wSash, SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER);
    wTable.setLinesVisible(false);
    wTable.addListener(
        SWT.Selection,
        e -> {
          int idx = wTable.getSelectionIndex();
          if (idx < 0) {
            currentExecutionHistory = null;
          } else {
            String key = wTable.getItem(idx).getText();
            currentExecutionHistory = executionHistories.get(key);
          }
          currentExecution = null;
          refreshTree();
        });
    PropsUi.setLook(wTable);

    TableColumn columnName = new TableColumn(wTable, SWT.LEFT);
    columnName.setWidth(ExecutionHistoryChart.LEFT_MARGIN_WIDTH);
    for (int i = 0; i < ExecutionHistoryManager.MAX_RUNS; i++) {
      TableColumn column = new TableColumn(wTable, SWT.LEFT);
      column.setWidth(ExecutionHistoryChart.CELL_WIDTH + ExecutionHistoryChart.PAD);
    }

    // Right side: tree of executions
    Composite rightComposite = new Composite(wSash, SWT.BORDER);
    rightComposite.setLayout(new FormLayout());
    PropsUi.setLook(rightComposite);

    // Create toolbar
    IToolbarContainer toolBarContainer =
        ToolbarFacade.createToolbarContainer(rightComposite, SWT.WRAP | SWT.LEFT | SWT.HORIZONTAL);
    toolBarWidgets = new GuiToolbarWidgets();
    toolBarWidgets.registerGuiPluginObject(this);
    toolBarWidgets.createToolbarWidgets(toolBarContainer, GUI_PLUGIN_TOOLBAR_PARENT_ID);
    Control toolBar = toolBarContainer.getControl();
    toolBar.setLayoutData(new FormDataBuilder().fullWidth().top().result());
    toolBar.pack();
    PropsUi.setLook(toolBar, Props.WIDGET_STYLE_TOOLBAR);

    wExecutionHistoryChart = new ExecutionHistoryChart(rightComposite, SWT.NONE);
    wExecutionHistoryChart.setLayoutData(
        new FormDataBuilder().fullWidth().top(toolBar).bottom().result());

    wSash.setWeights(25, 75);

    // Refresh the perspective when project changes.
    //
    hopGui
        .getEventsHandler()
        .addEventListener(
            getClass().getName(), e -> refresh(), HopGuiEvents.ProjectActivated.name());

    // Add key listeners
    HopGuiKeyHandler.getInstance().addParentObjectToHandle(this);
  }

  /** Reload all execution information locations and rebuild the execution groups. */
  @GuiToolbarElement(
      root = GUI_PLUGIN_TOOLBAR_PARENT_ID,
      id = TOOLBAR_ITEM_REFRESH,
      toolTip = "i18n::ExecutionHistoryPerspective.Toolbar.Refresh.Tooltip",
      image = "ui/images/refresh.svg")
  @GuiKeyboardShortcut(key = SWT.F5)
  @GuiOsxKeyboardShortcut(key = SWT.F5)
  public void refresh() {

    // Only refresh if we're actually displaying anything.
    if (!hopGui.isActivePerspective(this)
        || hopGui == null
        || wSash == null
        || wSash.isDisposed()) {
      return;
    }

    Shell shell = this.getShell();
    shell.setCursor(shell.getDisplay().getSystemCursor(SWT.CURSOR_WAIT));

    try {

      // If there are any cached locations, we want to close them before initializing new ones
      //
      for (ExecutionInfoLocation location : locationMap.values()) {
        location.getExecutionInfoLocation().close();
      }
      locationMap.clear();

      executionHistories.clear();

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

          IExecutionSelector selector =
              new DefaultExecutionSelector(
                  false, false, false, false, false, false, null, LastPeriod.TWO_MONTHS);

          for (String id : location.findExecutionIDs(selector)) {
            Execution execution = location.getExecution(id);

            if (execution != null) {

              ExecutionHistory history =
                  executionHistories.computeIfAbsent(
                      execution.getName(),
                      k -> new ExecutionHistory(execution.getExecutionType(), execution.getName()));

              ExecutionState state = location.getExecutionState(id, false);

              history
                  .getRuns()
                  .add(new ExecutionRun(execution, state, locationMeta.getName(), location));
            }
          }
        } catch (Exception ex) {
          LogChannel.GENERAL.logError(
              "Unable to initialize execution information location " + locationMeta.getName(), ex);
        }
      }

      // Sort each group's runs by registration date desc and truncate to MAX_RUNS.
      for (ExecutionHistory history : executionHistories.values()) {
        history
            .getRuns()
            .sort(
                Comparator.comparing(
                        (ExecutionRun r) ->
                            r.getExecution().getRegistrationDate() == null
                                ? new Date(0)
                                : r.getExecution().getRegistrationDate())
                    .reversed());
        if (history.getRuns().size() > ExecutionHistoryManager.MAX_RUNS) {
          history
              .getRuns()
              .subList(ExecutionHistoryManager.MAX_RUNS, history.getRuns().size())
              .clear();
        }

        // Collect the information of all the executed actions across the recent runs.
        LinkedHashMap<String, Integer> componentNames = new LinkedHashMap<>();
        long maxDuration = 0;
        for (ExecutionRun run : history.getRuns()) {
          try {

            // Find the maximum execution duration
            if (maxDuration < run.getDuration()) {
              maxDuration = run.getDuration();
            }

            // List all component execution states
            List<String> childIds =
                run.getLocation().findChildIds(ExecutionType.Workflow, run.getExecution().getId());

            for (String childId : childIds) {
              ExecutionData executionData =
                  run.getLocation().getExecutionData(run.getExecution().getId(), childId);

              // Action doesn't have state, return null (BUG ?)
              // ExecutionState childState = run.location.getExecutionState(childId);

              // Create execution state based on execution data
              ExecutionDataSetMeta dataSetMeta = executionData.getDataSetMeta();
              if (dataSetMeta != null) {
                String componentName = dataSetMeta.getName();

                componentNames.putIfAbsent(componentName, componentNames.size());

                // Add this one under that name
                ExecutionState state = new ExecutionState();
                state.setId(childId);
                state.setParentId(run.getExecution().getId());
                state.setName(componentName);
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
                run.getComponentStates().put(componentName, state);
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
        history.setComponentNames(new ArrayList<>(componentNames.keySet()));
      }

      // Refresh UI.
      if (wTable != null && !wTable.isDisposed()) {
        wTable.setRedraw(false);
        wTable.removeAll();
        for (String key : executionHistories.keySet()) {
          TableItem item = new TableItem(wTable, SWT.NONE);
          item.setText(key);
          item.setImage(GuiResource.getInstance().getImageWorkflow());

          ExecutionHistory history = executionHistories.get(key);

          if (history.getType() == ExecutionType.Workflow) {
            item.setImage(GuiResource.getInstance().getImageWorkflow());
          } else {
            item.setImage(GuiResource.getInstance().getImagePipeline());
          }

          int cols = history.getRuns().size();
          for (int c = 0; c < cols; c++) {
            ExecutionRun run = history.getRuns().get(c);
            Image image = ExecutionHistoryChart.getExecutionStateImage(run.getExecutionState());
            if (image != null) {
              item.setImage(c + 1, image);
            }
          }
        }
        wTable.setRedraw(true);
      }

      refreshTree();
    } catch (Exception e) {
      LogChannel.GENERAL.logError("Error refreshing monitoring perspective", e);
    } finally {
      this.getShell().setCursor(null);
    }
  }

  private void refreshTree() {
    wExecutionHistoryChart.setExecutionHistory(currentExecutionHistory);
  }
}
