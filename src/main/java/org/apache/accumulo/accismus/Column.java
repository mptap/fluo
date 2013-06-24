/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.accismus;

import java.io.UnsupportedEncodingException;

import org.apache.accumulo.core.data.ArrayByteSequence;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.security.ColumnVisibility;

/**
 * 
 */
public class Column {
  
  private static final ColumnVisibility EMPTY_VIS = new ColumnVisibility(new byte[0]);

  private ByteSequence family;
  private ByteSequence qualifier;
  private ColumnVisibility visibility = EMPTY_VIS;
  
  private static byte[] toBytes(String s) {
    try {
      return s.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }

  public int hashCode() {
    return family.hashCode() + qualifier.hashCode() + visibility.hashCode();
  }
  
  public boolean equals(Object o) {
    if (o instanceof Column) {
      Column oc = (Column) o;
      
      return family.equals(oc.family) && qualifier.equals(oc.qualifier) && visibility.equals(oc.visibility);
    }
    
    return false;
  }
  
  public Column(String family, String qualifier) {
    // TODO a limitation of this prototype...
    if (family.contains(Constants.SEP) || qualifier.contains(Constants.SEP)) {
      throw new IllegalArgumentException("columns can not contain : in prototype");
    }
    
    this.family = new ArrayByteSequence(toBytes(family));
    this.qualifier = new ArrayByteSequence(toBytes(qualifier));
  }
  
  public Column(byte[] family, byte[] qualifier) {
    this.family = new ArrayByteSequence(family);
    this.qualifier = new ArrayByteSequence(qualifier);
  }

  public Column(ByteSequence family, ByteSequence qualifier) {
    this.family = family;
    this.qualifier = qualifier;
  }
  
  public ByteSequence getFamily() {
    return family;
  }
  
  public ByteSequence getQualifier() {
    return qualifier;
  }

  public Column setVisibility(ColumnVisibility cv) {
    this.visibility = cv;
    return this;
  }
  
  public ColumnVisibility getVisibility() {
    return visibility;
  }

  public String toString() {
    return family + " " + qualifier + " " + visibility;
  }
}
