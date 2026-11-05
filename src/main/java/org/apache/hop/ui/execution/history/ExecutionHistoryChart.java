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

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import org.apache.hop.core.Const;
import org.apache.hop.execution.Execution;
import org.apache.hop.execution.IExecutionInfoLocation;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.ui.core.ConstUi;
import org.apache.hop.ui.core.PropsUi;
import org.apache.hop.ui.core.dialog.ErrorDialog;
import org.apache.hop.ui.core.dialog.MessageBox;
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

/**
 * Canvas drawing an {@link ExecutionHistory} as a chart.
 *
 * <ul>
 *   <li>The top area is a bar chart: one column per run, the bar height being the run duration and
 *       its color the run status.
 *   <li>Below, one row per component (transform or action) shows the status icon of that component
 *       for each run.
 * </ul>
 *
 * <p>Hovering a cell shows a tooltip, double-clicking a column opens the execution in the execution
 * perspective and the context menu allows to open or delete it.
 */
@NullMarked
public class ExecutionHistoryChart extends Canvas {

  private static final Class<?> PKG = ExecutionHistoryChart.class;

  /**
   * This formatter produces locale-specific date-time strings with medium-length formatting for
   * both date and time components.
   */
  private static final DateTimeFormatter DATETIME_FORMATTER =
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

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

  /** Icon of a paused component. */
  private final Image waitingImage;

  /** History currently displayed, or {@code null} until {@link #setExecutionHistory} is called. */
  private @Nullable ExecutionHistory executionHistory;

  /** Index of the run column under the mouse/selected, or -1 if none. */
  private int selectedExecution;

  /** Index of the component row under the mouse/selected, or -1 if none. */
  private int selectedComponent;

  /** Vertical scroll offset of the chart content, in pixels; 0 means scrolled to the top. */
  private int scrollOffset = 0;

  /**
   * Horizontal scroll offset of the run columns, in pixels; 0 means the most recent run is shown
   * next to the component names. The names and the duration axis don't scroll horizontally.
   */
  private int horizontalOffset = 0;

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

  /**
   * Creates an empty chart; call {@link #setExecutionHistory} to display data.
   *
   * @param parent the parent composite
   * @param style SWT style bits, combined with {@code NO_BACKGROUND | DOUBLE_BUFFERED | V_SCROLL |
   *     H_SCROLL}
   */
  public ExecutionHistoryChart(Composite parent, int style) {
    super(parent, style | SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED | SWT.V_SCROLL | SWT.H_SCROLL);

    finishedColor = new Color(92, 192, 196);
    runningColor = new Color(218, 170, 10);

    waitingImage =
        GuiResource.getInstance()
            .loadAsResource(parent.getDisplay(), "ui/images/waiting.svg", ConstUi.SMALL_ICON_SIZE);

    addListener(SWT.Paint, this::paint);
    addListener(SWT.MouseMove, this::mouseMove);
    addListener(SWT.MouseHover, this::mouseHover);
    addListener(SWT.MouseExit, this::mouseExit);
    addListener(SWT.MouseDoubleClick, this::mouseDoubleClick);
    addListener(SWT.Resize, e -> updateScrollBars());
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

    ScrollBar horizontalBar = getHorizontalBar();
    if (horizontalBar != null) {
      horizontalBar.addListener(
          SWT.Selection,
          e -> {
            horizontalOffset = horizontalBar.getSelection();
            redraw();
          });
    }
  }

  /**
   * Displays a new history, resetting the selection and the scroll position.
   *
   * @param executionHistory the history to display
   */
  public void setExecutionHistory(ExecutionHistory executionHistory) {
    this.executionHistory = executionHistory;
    this.selectedExecution = -1;
    this.selectedComponent = -1;
    this.scrollOffset = 0;
    this.horizontalOffset = 0;
    updateScrollBars();
    redraw();
  }

  /** Paints the whole chart and caches the layout fields used for hit-testing. */
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

    updateScrollBars();

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
    gc.setForeground(resource.getColorBlack());
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
        gc.setForeground(resource.getColorLightGray());
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

    // Columns scrolled to the left must not overwrite the component names
    gc.setClipping(nameWidth, 0, Math.max(0, area.width - nameWidth), area.height);

    List<String> componentNames = history.getComponentNames();
    List<ExecutionRun> runs = history.getRuns();
    for (int c = 0; c < runs.size(); c++) {
      ExecutionRun run = runs.get(c);

      int x = nameWidth + c * cellWidth - horizontalOffset;

      // Skip the columns outside the visible area
      if (x + cellWidth <= nameWidth || x >= area.width) {
        continue;
      }

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

      Color color = getExecutionStatusColor(run.getExecutionStatus());
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
        ExecutionStatus status = run.getComponentStatus().get(name);
        Image image = getExecutionStatusImage(status);

        int iconY = CHART_HEIGHT + line * lineHeight - scrollOffset + 5;
        if (image != null && iconY + image.getBounds().height >= 0 && iconY <= area.height) {
          gc.drawImage(image, x + pad, iconY);
        }

        line++;
      }
    }

    gc.setClipping((Rectangle) null);
  }

  /**
   * Adjusts the scroll bars to the content size, and clamps {@link #scrollOffset} and {@link
   * #horizontalOffset} to their new range.
   */
  private void updateScrollBars() {
    Rectangle area = getClientArea();

    // Vertically, the bar chart and all the component rows
    int contentHeight = CHART_HEIGHT + lineCount * lineHeight;
    scrollOffset =
        updateScrollBar(getVerticalBar(), contentHeight, area.height, scrollOffset, lineHeight);

    // Horizontally, only the run columns scroll, next to the fixed component names
    int runCount = executionHistory == null ? 0 : executionHistory.getRuns().size();
    horizontalOffset =
        updateScrollBar(
            getHorizontalBar(),
            runCount * cellWidth,
            Math.max(0, area.width - nameWidth),
            horizontalOffset,
            cellWidth);
  }

  /**
   * Adjusts a scroll bar to the content size, hiding it when everything fits.
   *
   * @param bar the scroll bar, may be null if the style doesn't have one
   * @param contentSize the size of the scrollable content, in pixels
   * @param visibleSize the size of the visible part of the content, in pixels
   * @param offset the current scroll offset, in pixels
   * @param increment the scroll step when clicking the bar arrows, in pixels
   * @return the offset clamped to the new range, 0 when everything fits
   */
  private static int updateScrollBar(
      @Nullable ScrollBar bar, int contentSize, int visibleSize, int offset, int increment) {
    if (bar == null) {
      return offset;
    }

    int maximumScrollOffset = Math.max(0, contentSize - visibleSize);

    if (maximumScrollOffset <= 0) {
      bar.setEnabled(false);
      bar.setMinimum(0);
      bar.setMaximum(1);
      bar.setThumb(1);
      bar.setSelection(0);
      bar.setVisible(false);
      return 0;
    }

    int clampedOffset = Math.min(offset, maximumScrollOffset);
    bar.setEnabled(true);
    bar.setVisible(true);
    bar.setMinimum(0);
    bar.setMaximum(contentSize);
    bar.setThumb(visibleSize);
    bar.setPageIncrement(visibleSize);
    bar.setIncrement(increment);
    bar.setSelection(clampedOffset);
    return clampedOffset;
  }

  /** Shows the context menu (open / delete) when a run column is selected. */
  private void menuDetect(Event event) {
    ExecutionRun run = getSelectedExecutionRun();
    if (run != null) {
      Menu menu = new Menu(this);

      MenuItem openItem = new MenuItem(menu, SWT.PUSH);
      openItem.setText(BaseMessages.getString(PKG, "ExecutionHistoryChart.Menu.OpenExecution"));
      openItem.addListener(SWT.Selection, e -> openExecution(run));

      new MenuItem(menu, SWT.SEPARATOR);

      MenuItem deleteItem = new MenuItem(menu, SWT.PUSH);
      deleteItem.setText(BaseMessages.getString(PKG, "ExecutionHistoryChart.Menu.DeleteExecution"));
      deleteItem.setImage(GuiResource.getInstance().getImageDelete());
      deleteItem.addListener(SWT.Selection, e -> deleteExecution(run));

      menu.setVisible(true);
    }
  }

  /**
   * Updates the selected run column and component row from the mouse position, using the layout
   * cached by the last {@link #paint}, and redraws only when the selection changed.
   */
  private void mouseMove(Event event) {
    int previousSelectedExecution = selectedExecution;
    int previousSelectedAction = selectedComponent;

    if (executionHistory != null) {
      int y = event.y + scrollOffset;

      if (y >= TOP_MARGIN_HEIGHT) {

        // Columns are scrolled horizontally, except over the component names
        int column =
            event.x < nameWidth
                ? -1
                : Math.floorDiv(event.x - nameWidth + horizontalOffset, cellWidth);
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

  /** Shows the run details over a bar, or the component status over a status icon. */
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
              run.getExecutionStatus().getDescription()));
    }
    // Mouse hover action execution state
    else {
      String name = executionHistory.getComponentNames().get(selectedComponent);
      ExecutionStatus executionStatus = run.getComponentStatus().get(name);
      if (executionStatus != null) {
        setToolTipText(executionStatus.getDescription());
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
    openExecution(getSelectedExecutionRun());
  }

  /**
   * Returns the run under the mouse.
   *
   * @return the selected run, or {@code null} if no column is selected
   */
  public @Nullable ExecutionRun getSelectedExecutionRun() {
    if (executionHistory != null && selectedExecution >= 0) {
      return executionHistory.getRun(selectedExecution);
    }
    return null;
  }

  /** Opens the run in the execution perspective; does nothing if {@code run} is null. */
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
      new ErrorDialog(
          getShell(),
          BaseMessages.getString(PKG, "System.Dialog.Error.Title"),
          BaseMessages.getString(PKG, "ExecutionHistoryChart.Error.OpenExecution"),
          e);
    }
  }

  /**
   * After confirmation, deletes the run from its location and removes it from the chart; does
   * nothing if {@code run} is null.
   */
  private void deleteExecution(@Nullable ExecutionRun run) {
    ExecutionHistory history = this.executionHistory;
    if (run == null || history == null) {
      return;
    }

    // The deletion can't be undone, ask for confirmation
    MessageBox box = new MessageBox(getShell(), SWT.YES | SWT.NO | SWT.ICON_QUESTION);
    box.setText(BaseMessages.getString(PKG, "ExecutionHistoryChart.DeleteExecution.Title"));
    box.setMessage(
        BaseMessages.getString(PKG, "ExecutionHistoryChart.DeleteExecution.Message", run.getId()));
    if (box.open() != SWT.YES) {
      return;
    }

    try {
      IExecutionInfoLocation location = run.getLocation();
      location.deleteExecution(run.getId());
      history.getRuns().remove(run);

      // The selected column may no longer exist
      selectedExecution = -1;
      selectedComponent = -1;
      setCursor(null);
      redraw();
    } catch (Exception e) {
      new ErrorDialog(
          getShell(),
          BaseMessages.getString(PKG, "System.Dialog.Error.Title"),
          BaseMessages.getString(PKG, "ExecutionHistoryChart.Error.DeleteExecution"),
          e);
    }
  }

  /**
   * Returns the bar color of a run status.
   *
   * @param status the run status, may be null
   * @return the color, light gray for a stale, unknown or null status
   */
  protected Color getExecutionStatusColor(@Nullable ExecutionStatus status) {
    GuiResource resource = GuiResource.getInstance();
    return switch (status) {
      case RUNNING -> runningColor;
      case PAUSED -> resource.getColorPurple();
      case STOPPED -> resource.getColorGray();
      case FAILED -> resource.getColorRed();
      case FINISHED -> finishedColor;
      case STALE, UNKNOWN -> resource.getColorLightGray();
      case null -> resource.getColorLightGray();
    };
  }

  /**
   * Returns the icon of a component status.
   *
   * @param status the component status, null when the component did not run
   * @return the icon, or {@code null} to draw nothing
   */
  protected @Nullable Image getExecutionStatusImage(@Nullable ExecutionStatus status) {
    GuiResource resource = GuiResource.getInstance();
    return switch (status) {
      case RUNNING -> resource.getImageBusy();
      case PAUSED -> waitingImage;
      case STOPPED -> resource.getImageErrorDisabled();
      case FAILED -> resource.getImageFailure();
      case FINISHED -> resource.getImageSuccess();
      case STALE -> resource.getImageWarning();
      case UNKNOWN -> resource.getImageError();
      case null -> null;
    };
  }

  /**
   * Picks the largest unit not greater than the given duration, used to format it.
   *
   * @param second a duration in seconds
   * @return {@code DAYS}, {@code HOURS}, {@code MINUTES} or {@code SECONDS}
   */
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

  /**
   * Formats a date with the locale medium date-time style, in the system time zone.
   *
   * @param date the date to format, may be null
   * @return the formatted date, or {@code "?"} if the date is null
   */
  protected static String formatDate(@Nullable Date date) {
    if (date == null) {
      return "?";
    }

    return DATETIME_FORMATTER.format(
        LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()));
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
