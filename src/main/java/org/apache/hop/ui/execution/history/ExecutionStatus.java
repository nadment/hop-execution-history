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

import org.apache.hop.execution.ExecutionState;
import org.apache.hop.i18n.BaseMessages;
import org.apache.hop.metadata.api.IEnumHasCodeAndDescription;

/** This enumeration describes the execution status for pipelines, workflows, transforms and actions. */
public enum ExecutionStatus implements IEnumHasCodeAndDescription {
  STOPPED("ExecutionStatus.Stopped"),
  STALE("ExecutionStatus.Stale"),
  FAILED("ExecutionStatus.Failed"),
  FINISHED("ExecutionStatus.Completed"),
  RUNNING("ExecutionStatus.Running"),
  UNKNOWN("ExecutionStatus.Unknown");

  private final String description;

  ExecutionStatus(String description) {
    this.description = BaseMessages.getString(ExecutionStatus.class, description);
  }

  @Override
  public String getCode() {
    return this.name();
  }

  @Override
  public String getDescription() {
    return description;
  }

  public static ExecutionStatus from(ExecutionState state, long loggingInterval) {
    if (state.isFailed()) {
      if ("Stopped".equals(state.getStatusDescription())) {
        return STOPPED;
      } else {
        return FAILED;
      }
    }
    if (state.isFinished()) {
      return FINISHED;
    }
    if (state.isStale(loggingInterval)) {
      return STALE;
    }
    if (state.isRunning()) {
      return RUNNING;
    }
    return UNKNOWN;
  }
}
