/*
 * Copyright 2014 Fluo authors (see AUTHORS)
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

package io.fluo.mapreduce;

import com.google.common.base.Charsets;
import io.fluo.accumulo.util.ColumnConstants;
import io.fluo.accumulo.values.WriteValue;
import io.fluo.api.data.Bytes;
import io.fluo.api.data.Column;
import io.fluo.core.util.ByteUtil;
import org.apache.accumulo.core.client.mapreduce.AccumuloFileOutputFormat;
import org.apache.accumulo.core.data.Key;
import org.apache.hadoop.io.Text;

/**
 * This class allows generating Accumulo key values that are in the Fluo data format. This class is
 * intended to be used with {@link AccumuloFileOutputFormat} inorder to seed an initialized Fluo
 * table on which no transactions have executed.
 * 
 * <p>
 * This class generates multiple Accumulo key values for a single Fluo row and column. The key
 * values generated are guaranteed to be in sorted order.
 * 
 * <p>
 * This class is designed to be reused inorder to avoid object creation in a map reduce job.
 * 
 * <p>
 * 
 * <pre>
 * {@code
 *   // this could be shared between calls to map or reduce, to avoid creating for each call.
 *   FluoKeyValueGenerator fkvg = new FluoKeyValueGenerator();
 *   // could also reuse column objects.
 *   Column column = new Column("fam1", "fam2");
 * 
 *   fkvg.setRow("row1").setColumn(column).setValue("val2");
 * 
 *   for (FluoKeyValue fluoKeyValue : fkvg.getKeyValues())
 *     writeToAccumuloFile(fluoKeyValue);
 * 
 *   fkvg.setRow("row2").setColumn(column).setValue("val3");
 * 
 *   // Each call to getKeyValues() returns the same objects populated with different data when
 *   // possible. So subsequent calls to getKeyValues() will create less objects. Of course this
 *   // invalidates what was returned by previous calls to getKeyValues().
 *   for (FluoKeyValue fluoKeyValue : fkvg.getKeyValues())
 *     writeToAccumuloFile(fluoKeyValue);
 * 
 * @code}
 * </pre>
 * 
 * <p>
 */

public class FluoKeyValueGenerator {

  private Column lastCol = null;
  private byte[] row;
  private byte[] fam;
  private byte[] qual;
  private byte[] vis;
  private byte[] val;
  private FluoKeyValue[] keyVals;

  public FluoKeyValueGenerator() {
    keyVals = new FluoKeyValue[2];
    keyVals[0] = new FluoKeyValue();
    keyVals[1] = new FluoKeyValue();
  }

  /**
   * @return this
   */
  public FluoKeyValueGenerator setRow(byte[] row) {
    this.row = row;
    return this;
  }

  /**
   * @return this
   */
  public FluoKeyValueGenerator setRow(Text row) {
    this.row = ByteUtil.toByteArray(row);
    return this;
  }

  /**
   * @return this
   */
  public FluoKeyValueGenerator setRow(Bytes row) {
    this.row = row.toArray();
    return this;
  }

  /**
   * This method will use UTF-8 to encode the string as bytes.
   * 
   * @return this
   */
  public FluoKeyValueGenerator setRow(String row) {
    this.row = row.getBytes(Charsets.UTF_8);
    return this;
  }

  /**
   * @return this
   */
  public FluoKeyValueGenerator setColumn(Column col) {
    if (col == lastCol) {
      // columns are immutable, so no need to recreate arrays again
      return this;
    }

    this.lastCol = col;
    this.fam = col.getFamily().toArray();
    this.qual = col.getQualifier().toArray();
    this.vis = col.getVisibility().toArray();
    return this;
  }

  /**
   * @return this
   */
  public FluoKeyValueGenerator setValue(byte[] val) {
    this.val = val;
    return this;
  }

  /**
   * @return this
   */
  public FluoKeyValueGenerator setValue(Text val) {
    this.val = ByteUtil.toByteArray(val);
    return this;
  }

  /**
   * @return this
   */
  public FluoKeyValueGenerator setValue(Bytes val) {
    this.val = val.toArray();
    return this;
  }

  /**
   * This method will use UTF-8 to encode the string as bytes.
   * 
   * @return this
   */
  public FluoKeyValueGenerator setValue(String val) {
    this.val = val.getBytes(Charsets.UTF_8);
    return this;
  }

  /**
   * Translates the Fluo row, column, and value set into the persistent format thats stored in
   * Accumulo.
   * 
   * <p>
   * The objects returned by this method are reused each time its called. So each time this is
   * called it invalidates what was retuned by previous calls to this method.
   * 
   * @return A an array of Accumulo key values in correct sorted order.
   */
  public FluoKeyValue[] getKeyValues() {
    FluoKeyValue kv = keyVals[0];
    kv.setKey(new Key(row, fam, qual, vis, ColumnConstants.WRITE_PREFIX | 1));
    kv.getValue().set(WriteValue.encode(0, false, false));

    kv = keyVals[1];
    kv.setKey(new Key(row, fam, qual, vis, ColumnConstants.DATA_PREFIX | 0));
    kv.getValue().set(val);

    return keyVals;
  }
}