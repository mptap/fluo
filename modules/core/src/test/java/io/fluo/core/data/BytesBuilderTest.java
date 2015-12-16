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

package io.fluo.core.data;

import io.fluo.api.data.Bytes;
import io.fluo.api.data.BytesBuilder;
import org.junit.Assert;
import org.junit.Test;

public class BytesBuilderTest {
  @Test
  public void testBasic() {
    BytesBuilder bb = Bytes.newBuilder();

    Bytes bytes1 = bb.append(new byte[] {'a', 'b'}).append("cd").append(Bytes.of("ef")).toBytes();
    Assert.assertEquals(Bytes.of("abcdef"), bytes1);

    bb = Bytes.newBuilder();
    Bytes bytes2 = bb.append(Bytes.of("ab")).append("cd").append(new byte[] {'e', 'f'}).toBytes();
    Assert.assertEquals(Bytes.of("abcdef"), bytes2);
  }

  @Test
  public void testSetLength() {
    BytesBuilder bb = Bytes.newBuilder();

    Bytes bytes1 = bb.append(new byte[] {'a', 'b'}).append("cd").append(Bytes.of("ef")).toBytes();
    Assert.assertEquals(Bytes.of("abcdef"), bytes1);

    bb.setLength(0);
    Bytes bytes2 = bb.append(Bytes.of("ab")).append("cd").append(new byte[] {'e', 'f'}).toBytes();
    Assert.assertEquals(Bytes.of("abcdef"), bytes2);


    bb.setLength(10);
    Bytes bytes3 = bb.toBytes();
    Assert.assertEquals(Bytes.of(new byte[] {'a', 'b', 'c', 'd', 'e', 'f', 0, 0, 0, 0}), bytes3);

    bb.setLength(100);
    Bytes bytes4 = bb.toBytes();
    Assert.assertEquals(Bytes.of("abcdef"), bytes4.subSequence(0, 6));
    for (int i = 6; i < 100; i++) {
      Assert.assertEquals(0, bytes4.byteAt(i));
    }
  }

  @Test
  public void testIncreaseCapacity() {

    // test appending 3 chars at a time
    StringBuilder sb = new StringBuilder();
    BytesBuilder bb = Bytes.newBuilder();
    BytesBuilder bb2 = Bytes.newBuilder();
    BytesBuilder bb3 = Bytes.newBuilder();
    int m = 19;
    for (int i = 0; i < 100; i++) {
      // produce a deterministic non repeating pattern
      String s = (m % 1000) + "";
      m = Math.abs(m * 19 + i);

      bb.append(s);
      bb2.append(Bytes.of(s));
      bb3.append(s);
      sb.append(s);
    }


    Assert.assertEquals(Bytes.of(sb.toString()), bb.toBytes());
    Assert.assertEquals(Bytes.of(sb.toString()), bb2.toBytes());
    Assert.assertEquals(Bytes.of(sb.toString()), bb3.toBytes());

    // test appending one char at a time
    sb.setLength(0);
    bb = Bytes.newBuilder();
    bb2 = Bytes.newBuilder();
    bb3.setLength(0);
    for (int i = 0; i < 500; i++) {
      // produce a deterministic non repeating pattern
      String s = (m % 10) + "";
      m = Math.abs(m * 19 + i);
      sb.append(s);
      bb.append(s);
      bb2.append(Bytes.of(s));
      bb3.append(s);

    }

    Assert.assertEquals(Bytes.of(sb.toString()), bb.toBytes());
    Assert.assertEquals(Bytes.of(sb.toString()), bb2.toBytes());
    Assert.assertEquals(Bytes.of(sb.toString()), bb3.toBytes());
  }
}
