/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.configuration.executor;

import org.apache.mesos.Protos.ExecutorID;

/**
 * Utility class for executor-related fields.
 */
public final class Executors {
  private Executors() {
    // Utility class.
  }

  public static final ExecutorID PLACEHOLDER_EXECUTOR_ID = ExecutorID.newBuilder()
      .setValue("PLACEHOLDER")
      .build();
}
