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

import static org.apache.hop.ui.execution.history.ExecutionHistoryChart.formatDate;
import static org.apache.hop.ui.execution.history.ExecutionHistoryChart.formatDuration;
import static org.apache.hop.ui.execution.history.ExecutionHistoryChart.getChronoUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.text.SimpleDateFormat;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExecutionHistoryChartTest {
  @ParameterizedTest(name = "{0}s -> {1}")
  @CsvSource({
    "0, SECONDS",
    "59, SECONDS",
    "60, MINUTES",
    "3599, MINUTES",
    "3600, HOURS",
    "86399, HOURS",
    "86400, DAYS"
  })
  void getChronoUnitPicksLargestApplicableUnit(long seconds, ChronoUnit expected) {
    assertEquals(expected, getChronoUnit(seconds));
  }

  @Test
  void formatDurationInSecondsOnly() {
    assertEquals("45s", formatDuration(45, ChronoUnit.SECONDS));
  }

  @Test
  void formatDurationInMinutesWithRemainingSeconds() {
    assertEquals("1m 5s", formatDuration(65, ChronoUnit.MINUTES));
  }

  @Test
  void formatDurationInExactMinutesOmitsSeconds() {
    assertEquals("1m", formatDuration(60, ChronoUnit.MINUTES));
  }

  @Test
  void formatDurationInHoursWithRemainingMinutes() {
    assertEquals("1h 1m", formatDuration(3660, ChronoUnit.HOURS));
  }

  @Test
  void formatDurationInExactHoursOmitsMinutes() {
    assertEquals("1h", formatDuration(3600, ChronoUnit.HOURS));
  }

  @Test
  void formatDurationInDaysWithRemainingHours() {
    assertEquals("1d 1h", formatDuration(90000, ChronoUnit.DAYS));
  }

  @Test
  void formatDurationInExactDaysOmitsHours() {
    assertEquals("1d", formatDuration(86400, ChronoUnit.DAYS));
  }

  @Test
  void formatDateReturnsPlaceholderForNullDate() {
    assertEquals("?", formatDate(null));
  }

  @Test
  void formatDateUsesFixedPattern() {
    Date date = new Date(0L);
    String expected = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").format(date);
    assertEquals(expected, formatDate(date));
  }
}
