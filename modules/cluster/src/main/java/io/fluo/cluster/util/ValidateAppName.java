/*
 * Copyright 2015 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.cluster.util;

import io.fluo.api.config.FluoConfiguration;

public class ValidateAppName {

  public static void main(String[] args) {
    if (args.length != 1) {
      System.out.println("ERROR - Expected usage: ValidateAppName <fluoApplicationName>");
      System.exit(-1);
    }

    FluoConfiguration config = new FluoConfiguration();
    try {
      config.setApplicationName(args[0]);
    } catch (IllegalArgumentException e) {
      System.out.println("ERROR - " + e.getMessage());
      System.exit(-1);
    }
  }
}
