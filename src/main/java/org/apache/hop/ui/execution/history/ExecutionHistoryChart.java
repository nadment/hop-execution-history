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

import java.text.SimpleDateFormat;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import org.apache.hop.core.Const;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.ExecutionState;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.dialog.ErrorDialog;
import org.apache.hop.ui.core.gui.GuiResource;
import org.apache.hop.ui.hopgui.perspective.execution.ExecutionPerspective;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class ExecutionHistoryChart extends Canvas {

  private static final Class<?> PKG = ExecutionHistoryChart.class;

  private static final String CONST_STOPPED = "Stopped";

  /**
   * Height in pixels, from the top of the canvas down to the duration-axis baseline (y = 0
   * duration). The bar chart's usable height is {@code CHART_HEIGHT - TOP_MARGIN_HEIGHT}.
   */
  public static final int CHART_HEIGHT = 220;

  /**
   * Unscaled width of one run column, in pixels. Multiplied by the native zoom factor before use.
   */
  public static final int CELL_WIDTH = 20;

  /**
   * Unscaled horizontal padding inside a cell, in pixels, used to inset the duration bar from the
   * cell's left/right edges. Multiplied by the native zoom factor before use.
   */
  public static final int PAD = 2;

  /** Space reserved above the bar chart area, in pixels, for the duration-axis legend. */
  private static final int TOP_MARGIN_HEIGHT = 20;

  /** Step durations in seconds. */
  private static final int[] TIME_STEPS = {
    1, 2, 5, 10, 15, 30, // Seconds
    60, 120, 300, 600, 900, 1800, // Minutes (1m, 2m, 5m, 10m, 15m, 30m)
    3600, 7200, 14400, 21600, 28800, 43200, // Hours (1h, 2h, 4h, 6h, 8h, 12h)
    86400, 172800, 432000, 604800, 1209600, 2592000 // Days (1d, 2d, 5d, 7d, 14d, 30d)
  };

  // Localized unit suffixes appended by formatDuration(), e.g. "2" + SUFFIX_DAY -> "2j".
  private static final String SUFFIX_DAY =
      BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Time.Day");
  private static final String SUFFIX_HOUR =
      BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Time.Hour");
  private static final String SUFFIX_MINUTE =
      BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Time.Minute");
  private static final String SUFFIX_SECOND =
      BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Time.Second");

  /** Color of a run's bar/icon when its execution finished successfully. */
  private final Color finishedColor;

  // TODO: replace with GuiResource.getInstance().getColorBusy() once that themed color exists.
  /** Color of a run's bar/icon while its execution is still running. */
  private final Color runningColor;

  /** History currently displayed, or {@code null} until {@link #setExecutionHistory} is called. */
  private @Nullable ExecutionHistory executionHistory;

  /** Index of the run column under the mouse/selected, or -1 if none. */
  private int selectedExecution;

  /** Index of the component row under the mouse/selected, or -1 if none. */
  private int selectedComponent;

  /** Vertical scroll offset of the chart content, in pixels; 0 means scrolled to the top. */
  private int scrollOffset = 0;

  // The following layout fields are recomputed on every paint() and cached so that mouseMove()
  // and mouseHover() can hit-test the last painted layout without redoing that work.

  /** Number of component rows to draw, i.e. {@code executionHistory.getComponentNames().size()}. */
  private int lineCount = 0;

  /** Height of one component row, in pixels: the larger of the cell width and the font height. */
  private int lineHeight;

  /** Width reserved on the left for the widest component name label, in pixels. */
  private int nameWidth;

  /** Zoom-adjusted width of one run column, in pixels (see {@link #CELL_WIDTH}). */
  private int cellWidth = CELL_WIDTH;

  public ExecutionHistoryChart(Composite parent, int style) {
    super(parent, style | SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED | SWT.V_SCROLL);

    finishedColor = new Color(92, 192, 196);
    runningColor = new Color(218, 170, 10);

    addListener(SWT.Paint, this::paint);
    addListener(SWT.MouseMove, this::mouseMove);
    addListener(SWT.MouseHover, this::mouseHover);
    addListener(SWT.MouseExit, this::mouseExit);
    addListener(SWT.MouseDoubleClick, this::mouseDoubleClick);
    addListener(SWT.Resize, e -> updateVerticalScrollBar());
    addListener(SWT.MenuDetect, this::menuDetect);

    ScrollBar verticalBar = getVerticalBar();
    if (verticalBar != null) {
      verticalBar.addListener(
          SWT.Selection,
          e -> {
            scrollOffset = verticalBar.getSelection();
            redraw();
          });
    }
  }

  public void setExecutionHistory(ExecutionHistory executionHistory) {
    this.executionHistory = executionHistory;
    this.selectedExecution = -1;
    this.selectedComponent = -1;
    this.scrollOffset = 0;
    updateVerticalScrollBar();
    redraw();
  }

  private void paint(Event e) {
    GuiResource resource = GuiResource.getInstance();

    GC gc = e.gc;
    gc.setFont(resource.getFontDefault());
    Rectangle area = getClientArea();

    double zoomFactor = PropsUi.getNativeZoomFactor();

    int fontHeight = gc.getFontMetrics().getHeight();
    int barAreaHeight = CHART_HEIGHT - TOP_MARGIN_HEIGHT;
    cellWidth = (int) (CELL_WIDTH * zoomFactor);
    int pad = (int) (PAD * zoomFactor);

    lineHeight = Math.max(cellWidth, fontHeight);

    // Erase background
    gc.setBackground(resource.getWidgetBackGroundColor());
    gc.fillRectangle(area);

    // Default value if nothing to display
    lineCount = 0;
    long maxDuration = 60;
    nameWidth = 300;

    if (executionHistory != null) {
      maxDuration = executionHistory.getMaxDuration();
      lineCount = executionHistory.getComponentNames().size();
      for (String name : executionHistory.getComponentNames()) {
        int width = gc.stringExtent(name).x + 10;
        if (nameWidth < width) {
          nameWidth = width;
        }
      }
    }

    updateVerticalScrollBar();

    int stepSeconds = computeStepSeconds(maxDuration);
    int maxSeconds = stepSeconds * 4;
    int stepPixel = (barAreaHeight * stepSeconds) / maxSeconds;

    drawDurationAxis(gc, resource, area, fontHeight, stepSeconds, stepPixel, maxSeconds);

    // Nothing to display
    if (executionHistory == null) {
      return;
    }

    drawComponentNames(gc, resource, area, fontHeight);
    drawColumns(gc, resource, area, barAreaHeight, cellWidth, pad, maxSeconds);
  }

  /** Find the nearest higher logical time step (in seconds) for the Y axis graduations. */
  private static int computeStepSeconds(long maxDuration) {
    int rawStepSeconds = (int) Math.ceil(maxDuration / 4f);
    if (rawStepSeconds == 0) {
      rawStepSeconds = 1;
    }
    for (int step : TIME_STEPS) {
      if (step >= rawStepSeconds) {
        return step;
      }
    }
    return TIME_STEPS[TIME_STEPS.length - 1];
  }

  /** Draw the Y axis grid lines, duration labels and legend. */
  private void drawDurationAxis(
      GC gc,
      GuiResource resource,
      Rectangle area,
      int fontHeight,
      int stepSeconds,
      int stepPixel,
      int maxSeconds) {

    // Draw the grid lines
    gc.setLineStyle(SWT.LINE_DOT);
    gc.setForeground(resource.getColorGray());
    int seconds = 0;
    ChronoUnit unit = getChronoUnit(maxSeconds);

    // Draw the graduations
    String legend = BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Label");
    int durationWith = Math.max(gc.stringExtent(legend).x, 80);

    for (int graduation = 0; graduation < 4; graduation++) {
      int y = CHART_HEIGHT - graduation * stepPixel - scrollOffset;

      String label = formatDuration(seconds, unit);
      seconds += stepSeconds;
      gc.drawString(label, nameWidth - durationWith, y - fontHeight);
      gc.drawLine(nameWidth - 5, y, area.width, y);
    }

    // Draw vertical axis legend
    gc.drawString(legend, nameWidth - durationWith, -scrollOffset);
    gc.setLineStyle(SWT.LINE_SOLID);
  }

  /** Draw the row labels (action names) and the highlight of the selected row. */
  private void drawComponentNames(GC gc, GuiResource resource, Rectangle area, int fontHeight) {
    ExecutionHistory history = this.executionHistory;
    if (history == null) {
      return;
    }

    // Draw action name
    int y = CHART_HEIGHT - scrollOffset + 2;
    for (String name : history.getComponentNames()) {
      if (y + lineHeight >= 0 && y <= area.height) {
        gc.drawString(name, 4, y + (lineHeight - fontHeight) / 2);
      }
      y += lineHeight;
    }

    // Draw selected action
    if (selectedComponent >= 0) {
      int selectedActionY = CHART_HEIGHT + 2 + selectedComponent * lineHeight - scrollOffset;
      if (selectedActionY + lineHeight >= 0 && selectedActionY <= area.height) {
        gc.setBackground(resource.getColorLightGray());
        gc.setLineStyle(SWT.LINE_SOLID);
        gc.drawRectangle(0, selectedActionY, area.width - 1, lineHeight);
      }
    }
  }

  /** Draw one column per run: the duration bar, the selection highlight and the state icons. */
  private void drawColumns(
      GC gc,
      GuiResource resource,
      Rectangle area,
      int barAreaHeight,
      int cellWidth,
      int pad,
      int maxSeconds) {
    ExecutionHistory history = this.executionHistory;
    if (history == null) {
      return;
    }

    List<String> componentNames = history.getComponentNames();
    List<ExecutionRun> runs = history.getRuns();
    for (int c = 0; c < runs.size(); c++) {
      ExecutionRun run = runs.get(c);

      int x = nameWidth + c * cellWidth;
      int barHeight = 0;
      if (maxSeconds > 0) {
        barHeight = (int) (barAreaHeight * run.getDuration() / maxSeconds);
      }

      // Draw vertical selection
      if (c == selectedExecution) {
        gc.setBackground(resource.getColorLightGray());
        gc.fillRectangle(
            x,
            TOP_MARGIN_HEIGHT - scrollOffset,
            cellWidth,
            barAreaHeight + componentNames.size() * lineHeight);
      }

      Color color = getExecutionStateColor(run.getExecutionState());
      if (barHeight > 0) {
        gc.setBackground(color);
        gc.fillRoundRectangle(
            x + pad, CHART_HEIGHT + 2 - scrollOffset, cellWidth - 2 * pad, -barHeight, 3, 3);
      } else {
        gc.setForeground(color);
        gc.drawLine(
            x + pad,
            CHART_HEIGHT - scrollOffset,
            x + cellWidth - 2 * pad,
            CHART_HEIGHT - scrollOffset);
      }

      // Draw action execution state icon
      int line = 0;
      for (String name : componentNames) {
        ExecutionState state = run.getComponentStates().get(name);
        Image image = getExecutionStateImage(state);

        int iconY = CHART_HEIGHT + line * lineHeight - scrollOffset + 5;
        if (image != null && iconY + image.getBounds().height >= 0 && iconY <= area.height) {
          gc.drawImage(image, x + pad, iconY);
        }

        line++;
      }
    }
  }

  private void updateVerticalScrollBar() {
    ScrollBar verticalBar = getVerticalBar();
    if (verticalBar == null) {
      return;
    }

    Rectangle area = getClientArea();
    int contentHeight = CHART_HEIGHT + lineCount * lineHeight;
    int visibleHeight = area.height;
    int maximumScrollOffset = Math.max(0, contentHeight - visibleHeight);

    if (maximumScrollOffset <= 0) {
      scrollOffset = 0;
      verticalBar.setEnabled(false);
      verticalBar.setMinimum(0);
      verticalBar.setMaximum(1);
      verticalBar.setThumb(1);
      verticalBar.setSelection(0);
      verticalBar.setVisible(false);
      return;
    }

    scrollOffset = Math.min(scrollOffset, maximumScrollOffset);
    verticalBar.setEnabled(true);
    verticalBar.setVisible(true);
    verticalBar.setMinimum(0);
    verticalBar.setMaximum(contentHeight);
    verticalBar.setThumb(visibleHeight);
    verticalBar.setPageIncrement(visibleHeight);
    verticalBar.setIncrement(lineHeight);
    verticalBar.setSelection(scrollOffset);
  }

  private void menuDetect(Event event) {
    ExecutionRun run = getSelectedExecutionRun();
    if (run != null) {
      Menu menu = new Menu(this);
      MenuItem openItem = new MenuItem(menu, SWT.PUSH);
      openItem.setText(BaseMessages.getString(PKG, "ExecutionHistoryChart.Menu.OpenExecution"));
      openItem.addListener(SWT.Selection, e -> openExecution(run));
      menu.setVisible(true);
    }
  }

  private void mouseMove(Event event) {
    int previousSelectedExecution = selectedExecution;
    int previousSelectedAction = selectedComponent;

    if (executionHistory != null) {
      int y = event.y + scrollOffset;

      if (y >= TOP_MARGIN_HEIGHT) {

        int column = Math.floorDiv(event.x - nameWidth, cellWidth);
        if (column >= 0 && column < executionHistory.getRuns().size()) {
          selectedExecution = column;
        } else {
          selectedExecution = -1;
        }

        int line = Math.floorDiv(y - CHART_HEIGHT, lineHeight);
        if (line >= 0 && line < lineCount) {
          selectedComponent = line;
        } else {
          selectedComponent = -1;
          if (line >= lineCount) {
            selectedExecution = -1;
          }
        }
      }

      if (previousSelectedExecution != selectedExecution
          || previousSelectedAction != selectedComponent) {

        // Set hand cursor when mouse hovers a column
        if (selectedExecution >= 0) {
          setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        } else {
          setCursor(null);
        }

        redraw();
      }
    }
  }

  private void mouseHover(Event event) {

    // Reset tooltip
    setToolTipText(null);

    if (executionHistory == null || selectedExecution < 0) {
      return;
    }

    // Set the tooltip
    ExecutionRun run = executionHistory.getRun(selectedExecution);
    // Mouse hover execution bars
    if (selectedComponent < 0) {
      setToolTipText(
          BaseMessages.getString(
              PKG,
              "ExecutionHistoryChart.Execution.Tooltip",
              run.getId(),
              Const.NVL(run.getParentId(), ""),
              run.getRunConfigurationName(),
              formatDate(run.getExecutionStartDate()),
              formatDate(run.getExecutionState().getExecutionEndDate()),
              formatDuration(run.getDuration(), getChronoUnit(run.getDuration())),
              getExecutionStateName(run.getExecutionState())));

    }
    // Mouse hover action execution state
    else {
      String name = executionHistory.getComponentNames().get(selectedComponent);
      ExecutionState executionState = run.getComponentStates().get(name);
      setToolTipText(getExecutionStateName(executionState));
    }
  }

  private void mouseExit(Event event) {
    // Reset selections
    selectedExecution = -1;
    selectedComponent = -1;
    redraw();
  }

  private void mouseDoubleClick(Event event) {
    openExecution(getSelectedExecutionRun());
  }

  public @Nullable ExecutionRun getSelectedExecutionRun() {
    if (executionHistory != null && selectedExecution >= 0) {
      return executionHistory.getRun(selectedExecution);
    }
    return null;
  }

  private void openExecution(@Nullable ExecutionRun run) {
    if (run == null) {
      return;
    }

    try {
      ExecutionPerspective perspective = ExecutionPerspective.getInstance();

      String locationName = run.getLocationName();

      // Active the perspective before to avoid NPE when never used before and getLocationMap is
      // empty.
      perspective.activate();

      // Open execution viewer
      Execution execution = run.getLocation().getExecution(run.getId());
      perspective.createExecutionViewer(locationName, execution, run.getExecutionState());
    } catch (Exception e) {
      new ErrorDialog(getShell(), "Error", "Error showing viewer for execution", e);
    }
  }

  protected Color getExecutionStateColor(@Nullable ExecutionState state) {
    GuiResource resource = GuiResource.getInstance();
    if (state != null) {
      if (state.isFailed()) {
        if (CONST_STOPPED.equals(state.getStatusDescription())) {
          return resource.getColorGray();
        }
        return resource.getColorRed();
      }
      if (state.isFinished()) {
        return finishedColor;
      }
      if (state.isRunning()) {
        return runningColor;
      }
    }
    return resource.getColorLightGray();
  }

  protected @Nullable String getExecutionStateName(@Nullable ExecutionState state) {
    if (state != null) {
      if (state.isFailed()) {
        if (CONST_STOPPED.equals(state.getStatusDescription())) {
          return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Stopped");
        }

        return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Failed");
      }
      if (state.isFinished()) {
        return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Completed");
      }
      if (state.isRunning()) {
        return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Running");
      }

      return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Unknown");
    }
    return null;
  }

  protected static @Nullable Image getExecutionStateImage(@Nullable ExecutionState state) {
    if (state == null) {
      return null;
    }
    GuiResource resource = GuiResource.getInstance();
    if (state.isFailed()) {
      if (CONST_STOPPED.equals(state.getStatusDescription())) {
        return resource.getImageErrorDisabled();
      }
      return resource.getImageFailure();
    }
    if (state.isFinished()) {
      return resource.getImageSuccess();
    }
    if (state.isRunning()) {
      return resource.getImageBusy();
    }
    return null;
  }

  protected static ChronoUnit getChronoUnit(long second) {
    if (second >= 86400) {
      return ChronoUnit.DAYS;
    } else if (second >= 3600) {
      return ChronoUnit.HOURS;
    } else if (second >= 60) {
      return ChronoUnit.MINUTES;
    }
    return ChronoUnit.SECONDS;
  }

  protected static String formatDate(@Nullable Date date) {
    if (date == null) {
      return "?";
    }

    // TODO: use Local or Const.HOP_DEFAULT_DATE_FORMAT to format date time
    SimpleDateFormat formatter = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    return formatter.format(date);
  }

  /**
   * Formats a duration in seconds into a human-readable string representation based on the
   * specified time unit. The formatted string includes the primary unit and the next smaller unit
   * if applicable. For DAYS: displays days and hours (if hours > 0). For HOURS: displays hours and
   * minutes (if minutes > 0). For MINUTES: displays minutes and seconds (if seconds > 0). For other
   * units: displays only seconds.
   *
   * @param duration the total duration in seconds to format
   * @param unit the chronological unit to use as the primary unit for formatting
   * @return a formatted duration string with appropriate suffixes
   */
  protected static String formatDuration(long duration, ChronoUnit unit) {
    return switch (unit) {
      case DAYS -> {
        long days = duration / 86400;
        long hours = (duration % 86400) / 3600;
        yield hours > 0 ? days + SUFFIX_DAY + ' ' + hours + SUFFIX_HOUR : days + SUFFIX_DAY;
      }
      case HOURS -> {
        long hours = duration / 3600;
        long minutes = (duration % 3600) / 60;
        yield minutes > 0
            ? hours + SUFFIX_HOUR + ' ' + minutes + SUFFIX_MINUTE
            : hours + SUFFIX_HOUR;
      }
      case MINUTES -> {
        long minutes = duration / 60;
        long seconds = duration % 60;
        yield seconds > 0
            ? minutes + SUFFIX_MINUTE + ' ' + seconds + SUFFIX_SECOND
            : minutes + SUFFIX_MINUTE;
      }
      default -> duration + SUFFIX_SECOND;
    };
  }
}
