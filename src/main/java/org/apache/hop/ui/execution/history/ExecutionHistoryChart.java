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
import org.apache.hop.core.Const;
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
import org.eclipse.swt.widgets.ScrollBar;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class ExecutionHistoryChart extends Canvas {

  private static final Class<?> PKG = ExecutionHistoryChart.class;

  // TODO: use Local or Const.HOP_DEFAULT_DATE_FORMAT to format date time
  private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

  /** Height of the bar chart area. */
  public static final int CHART_HEIGHT = 220;

  /** Cell width in pixels */
  public static final int CELL_WIDTH = 20;

  /** Padding between cells */
  public static final int PAD = 2;

  /** Chart top margin height */
  private static final int TOP_MARGIN_HEIGHT = 20;

  /** Row label width */
  public static final int LEFT_MARGIN_WIDTH = 300;

  /** Logical time steps in seconds */
  private static final int[] TIME_STEPS = {
    1, 2, 5, 10, 15, 30, // Seconds
    60, 120, 300, 600, 900, 1800, // Minutes (1m, 2m, 5m, 10m, 15m, 30m)
    3600, 7200, 14400, 21600, 28800, 43200, // Hours (1h, 2h, 4h, 6h, 8h, 12h)
    86400, 172800, 432000, 604800, 1209600, 2592000 // Days (1d, 2d, 5d, 7d, 14d, 30d)
  };

  private static final String SUFFIX_DAY =
      BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Time.Day");
  private static final String SUFFIX_HOUR =
      BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Time.Hour");
  private static final String SUFFIX_MINUTE =
      BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Time.Minute");
  private static final String SUFFIX_SECOND =
      BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Time.Second");

  private final Color finishedColor;
  private final Color colorBusy;
  private @Nullable ExecutionHistory executionHistory;
  private int selectedExecution;
  private int selectedComponent;

  // Vertical scrolling offset
  private int scrollOffset = 0;

  private int lineCount = 0;

  // Line height depends on the font size
  private int lineHeight;

  public ExecutionHistoryChart(Composite parent, int style) {
    super(parent, style | SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED | SWT.V_SCROLL);

    finishedColor = new Color(92, 192, 196);
    colorBusy = new Color(218, 170, 10);

    addListener(SWT.Paint, this::paint);
    addListener(SWT.MouseMove, this::mouseMove);
    addListener(SWT.MouseHover, this::mouseHover);
    addListener(SWT.MouseExit, this::mouseExit);
    addListener(SWT.MouseDoubleClick, this::mouseDoubleClick);
    addListener(SWT.Resize, e -> updateVerticalScrollBar());

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
    int cellWidth = (int) (CELL_WIDTH * zoomFactor);
    int pad = (int) (PAD * zoomFactor);

    lineHeight = Math.max(cellWidth, fontHeight);

    // Erase background
    gc.setBackground(resource.getWidgetBackGroundColor());
    gc.fillRectangle(area);

    // Nothing to display
    long maxDuration = 0;
    if (executionHistory == null) {
      lineCount = 0;
      maxDuration = 60;
    } else {
      lineCount = executionHistory.getComponentNames().size();
      maxDuration = executionHistory.getMaxDuration();
    }

    updateVerticalScrollBar();

    // Search for the nearest higher logical step for Y axis
    int rawStepSeconds = (int) Math.ceil(maxDuration / 4f);
    if (rawStepSeconds == 0) rawStepSeconds = 1;
    int stepSeconds = TIME_STEPS[TIME_STEPS.length - 1];
    for (int step : TIME_STEPS) {
      if (step >= rawStepSeconds) {
        stepSeconds = step;
        break;
      }
    }
    int maxSeconds = stepSeconds * 4;
    int stepPixel = (barAreaHeight * stepSeconds) / maxSeconds;

    // Draw grid lines
    gc.setLineStyle(SWT.LINE_DOT);
    gc.setForeground(resource.getColorGray());
    int seconds = 0;
    ChronoUnit unit = getChronoUnit(maxSeconds);
    for (int graduation = 0; graduation < 4; graduation++) {
      int y = CHART_HEIGHT - graduation * stepPixel - scrollOffset;

      String label = formatDuration(seconds, unit);
      seconds += stepSeconds;
      gc.drawString(label, LEFT_MARGIN_WIDTH - 80, y - fontHeight);
      gc.drawLine(LEFT_MARGIN_WIDTH - 5, y, area.width, y);
    }

    // Draw vertical axis legend
    gc.drawString(
        BaseMessages.getString(PKG, "ExecutionHistoryChart.Duration.Label"),
        LEFT_MARGIN_WIDTH - 80,
        -scrollOffset);

    // Nothing to display
    if (executionHistory == null) {
      return;
    }

    // Draw action name
    int y = CHART_HEIGHT - scrollOffset;
    for (String name : executionHistory.getComponentNames()) {
      if (y + lineHeight >= 0 && y <= area.height) {
        gc.drawString(name, 4, y + (lineHeight - fontHeight) / 2);
      }
      y += lineHeight;
    }

    // Draw selected action
    if (selectedComponent >= 0) {
      int selectedActionY = CHART_HEIGHT + selectedComponent * lineHeight - scrollOffset;
      if (selectedActionY + lineHeight >= 0 && selectedActionY <= area.height) {
        Color selectionBackground = resource.getColorLightGray();
        gc.setBackground(selectionBackground);
        gc.setLineStyle(SWT.LINE_SOLID);
        gc.drawRectangle(0, selectedActionY, area.width - 1, lineHeight);
      }
    }

    // Draw the column headers with execution duration
    int cols = executionHistory.getRuns().size();
    for (int c = 0; c < cols; c++) {
      ExecutionRun run = executionHistory.getRuns().get(c);

      int x = LEFT_MARGIN_WIDTH + c * cellWidth;
      int barHeight = 0;
      if (maxSeconds > 0) {
        barHeight = (int) (barAreaHeight * run.duration / maxSeconds);
      }

      // Draw vertical selection
      if (c == selectedExecution) {
        Color selectionBackground = resource.getColorLightGray();
        gc.setBackground(selectionBackground);
        gc.fillRectangle(
            x,
            TOP_MARGIN_HEIGHT - scrollOffset,
            cellWidth,
            barAreaHeight + executionHistory.getComponentNames().size() * lineHeight);
      }

      gc.setBackground(getExecutionStateColor(run.executionState));
      gc.fillRoundRectangle(
          x + pad, CHART_HEIGHT - barHeight - scrollOffset, cellWidth - 2 * pad, barHeight, 4, 4);

      // Draw action execution state icon
      int line = 0;
      for (String name : executionHistory.getComponentNames()) {
        ExecutionState state = run.componentStates.get(name);
        Image image = getExecutionStateImage(state);

        int iconY = CHART_HEIGHT + line * lineHeight - scrollOffset + 2;
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

  private void mouseMove(Event event) {
    int previousSelectedExecution = selectedExecution;
    int previousSelectedAction = selectedComponent;

    if (executionHistory != null) {
      double zoomFactor = PropsUi.getNativeZoomFactor();

      int y = event.y + scrollOffset;

      if (y >= TOP_MARGIN_HEIGHT) {

        int column = Math.floorDiv(event.x - LEFT_MARGIN_WIDTH, (int) (CELL_WIDTH * zoomFactor));
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

    // Set the tooltip
    if (selectedExecution >= 0) {
      ExecutionRun run = this.executionHistory.getRun(selectedExecution);
      // Mouse hover execution bars
      if (selectedComponent < 0) {
        setToolTipText(
            BaseMessages.getString(
                PKG,
                "ExecutionHistoryChart.Execution.Tooltip",
                run.execution.getId(),
                Const.NVL(run.execution.getParentId(), ""),
                run.execution.getRunConfigurationName(),
                formatDate(run.execution.getExecutionStartDate()),
                formatDate(run.executionState.getExecutionEndDate()),
                formatDuration((int) run.duration, getChronoUnit(run.duration)),
                getExecutionStateName(run.executionState)));

      }
      // Mouse hover action execution state
      else {
        String name = executionHistory.getComponentNames().get(selectedComponent);
        ExecutionState executionState = run.componentStates.get(name);
        setToolTipText(getExecutionStateName(executionState));
      }
    }
  }

  private void mouseExit(Event event) {
    // Reset selections
    selectedExecution = -1;
    selectedComponent = -1;
    redraw();
  }

  private void mouseDoubleClick(Event event) {
    if (selectedExecution >= 0) {
      try {
        ExecutionRun run = this.executionHistory.getRun(selectedExecution);
        String locationName = run.locationName;
        ExecutionPerspective perspective = ExecutionPerspective.getInstance();

        // Active the perspective before to avoid NPE when never used before and getLocationMap is
        // empty.
        perspective.activate();

        // Open execution viewer
        perspective.createExecutionViewer(locationName, run.execution, run.executionState);
      } catch (Exception e) {
        new ErrorDialog(getShell(), "Error", "Error showing viewer for execution", e);
      }
    }
  }

  private Color getExecutionStateColor(@Nullable ExecutionState state) {
    if (state != null) {
      if (state.isFailed()) {
        if ("Stopped".equals(state.getStatusDescription())) {
          return GuiResource.getInstance().getColorGray();
        }
        return GuiResource.getInstance().getColorRed();
      }
      if (state.isRunning()) {
        // TODO:
        // return GuiResource.getInstance().getColorBusy();
        return colorBusy;
      }
      if (state.isFinished()) {
        return finishedColor;
      }
    }
    return GuiResource.getInstance().getColorLightGray();
  }

  private @Nullable String getExecutionStateName(@Nullable ExecutionState state) {
    if (state != null) {
      if (state.isFailed()) {
        if ("Stopped".equals(state.getStatusDescription())) {
          return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Stopped");
        }

        return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Failed");
      }
      if (state.isRunning()) {
        return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Running");
      }
      if (state.isFinished()) {
        return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Completed");
      }

      return BaseMessages.getString(PKG, "ExecutionHistoryChart.State.Unknown");
    }
    return null;
  }

  public static @Nullable Image getExecutionStateImage(@Nullable ExecutionState state) {
    if (state != null) {
      if (state.isFailed()) {
        if ("Stopped".equals(state.getStatusDescription())) {
          return GuiResource.getInstance().getImageErrorDisabled();
        }
        return GuiResource.getInstance().getImageFailure();
      }
      if (state.isRunning()) {
        return GuiResource.getInstance().getImageBusy();
      }
      if (state.isFinished()) {
        return GuiResource.getInstance().getImageSuccess();
      }
    }
    return null;
  }

  private ChronoUnit getChronoUnit(long second) {
    if (second >= 86400) {
      return ChronoUnit.DAYS;
    } else if (second >= 3600) {
      return ChronoUnit.HOURS;
    } else if (second >= 60) {
      return ChronoUnit.MINUTES;
    }
    return ChronoUnit.SECONDS;
  }

  private String formatDate(@Nullable Date date) {
    return date == null ? "?" : DATE_FORMAT.format(date);
  }

  private String formatDuration(long totalSeconds, ChronoUnit unit) {
    switch (unit) {
      case DAYS:
        {
          long days = totalSeconds / 86400;
          long hours = (totalSeconds % 86400) / 3600;
          return hours > 0 ? days + SUFFIX_DAY + ' ' + hours + SUFFIX_HOUR : days + SUFFIX_DAY;
        }
      case HOURS:
        {
          long hours = totalSeconds / 3600;
          long minutes = (totalSeconds % 3600) / 60;
          return minutes > 0
              ? hours + SUFFIX_HOUR + ' ' + minutes + SUFFIX_MINUTE
              : hours + SUFFIX_HOUR;
        }
      case MINUTES:
        {
          long minutes = totalSeconds / 60;
          long seconds = totalSeconds % 60;
          return seconds > 0
              ? minutes + SUFFIX_MINUTE + ' ' + seconds + SUFFIX_SECOND
              : minutes + SUFFIX_MINUTE;
        }
      case SECONDS:
      default:
        return totalSeconds + SUFFIX_SECOND;
    }
  }
}
