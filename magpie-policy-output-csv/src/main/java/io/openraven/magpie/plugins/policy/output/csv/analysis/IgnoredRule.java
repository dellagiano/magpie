/*
 * Copyright 2021 Open Raven Inc
 *
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
package io.openraven.magpie.plugins.policy.output.csv.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.openraven.magpie.plugins.policy.output.csv.model.Policy;
import io.openraven.magpie.plugins.policy.output.csv.model.Rule;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IgnoredRule {

  private Policy policy;
  private Rule rule;
  private IgnoredReason ignoredReason;

  public Rule getRule() {
    return rule;
  }

  public IgnoredReason getIgnoredReason() {
    return ignoredReason;
  }

  public Policy getPolicy() {
    return policy;
  }

  @Override
  public String toString() {
    return "IgnoredRule{" +
      "policy=" + policy +
      ", rule=" + rule +
      ", ignoredReason=" + ignoredReason +
      '}';
  }

  public enum IgnoredReason {
    DISABLED("Disabled via configuration"),
    MISSING_ASSET("Asset not found"),
    MANUAL_CONTROL("Manual Control");

    private final String reason;

    IgnoredReason(String reason) {
      this.reason = reason;
    }

    public String getReason() {
      return reason;
    }
  }
}
