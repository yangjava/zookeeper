/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jute;

import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.TreeMap;

/**
 *
 */
public class BinaryOutputArchive implements OutputArchive {
    // 字节缓冲
    private ByteBuffer bb = ByteBuffer.allocate(1024);
    // DataInput接口，用于从二进制流中读取字节
    private DataOutput out;
    // 静态方法，用于获取Archive
    public static BinaryOutputArchive getArchive(OutputStream strm) {
        return new BinaryOutputArchive(new DataOutputStream(strm));
    }

    /**
     * Creates a new instance of BinaryOutputArchive.
     */
    // 构造函数
    public BinaryOutputArchive(DataOutput out) {
        this.out = out;
    }
    // 写Byte类型
    public void writeByte(byte b, String tag) throws IOException {
        out.writeByte(b);
    }
    // 写boolean类型
    public void writeBool(boolean b, String tag) throws IOException {
        out.writeBoolean(b);
    }
    // 写int类型
    public void writeInt(int i, String tag) throws IOException {
        out.writeInt(i);
    }
    // 写long类型
    public void writeLong(long l, String tag) throws IOException {
        out.writeLong(l);
    }
    // 写float类型
    public void writeFloat(float f, String tag) throws IOException {
        out.writeFloat(f);
    }
    // 写double类型
    public void writeDouble(double d, String tag) throws IOException {
        out.writeDouble(d);
    }

    /**
     * create our own char encoder to utf8. This is faster
     * then string.getbytes(UTF8).
     *
     * @param s the string to encode into utf8
     * @return utf8 byte sequence.
     */
    // 将String类型转化为ByteBuffer类型
    private ByteBuffer stringToByteBuffer(CharSequence s) {
        // 清空ByteBuffer
        bb.clear();
        // s的长度
        final int len = s.length();
        // 遍历s
        for (int i = 0; i < len; i++) {
            // ByteBuffer剩余大小小于3
            if (bb.remaining() < 3) {
                // 再进行一次分配(扩大一倍)
                ByteBuffer n = ByteBuffer.allocate(bb.capacity() << 1);
                // 切换方式
                bb.flip();
                // 写入bb
                n.put(bb);
                bb = n;
            }
            char c = s.charAt(i);
            // 小于128，直接写入
            if (c < 0x80) {
                bb.put((byte) c);
                // 小于2048，则进行相应处理
            } else if (c < 0x800) {
                bb.put((byte) (0xc0 | (c >> 6)));
                bb.put((byte) (0x80 | (c & 0x3f)));
            } else {
                // 大于2048，则进行相应处理
                bb.put((byte) (0xe0 | (c >> 12)));
                bb.put((byte) (0x80 | ((c >> 6) & 0x3f)));
                bb.put((byte) (0x80 | (c & 0x3f)));
            }
        }
        // 切换方式
        bb.flip();
        return bb;
    }
    // 写String类型
    public void writeString(String s, String tag) throws IOException {
        if (s == null) {
            writeInt(-1, "len");
            return;
        }
        ByteBuffer bb = stringToByteBuffer(s);
        writeInt(bb.remaining(), "len");
        out.write(bb.array(), bb.position(), bb.limit());
    }
    // 写Buffer类型
    public void writeBuffer(byte[] barr, String tag)
            throws IOException {
        if (barr == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(barr.length);
        out.write(barr);
    }
    // 写Record类型
    public void writeRecord(Record r, String tag) throws IOException {
        r.serialize(this, tag);
    }
    // 开始写Record
    public void startRecord(Record r, String tag) throws IOException {
    }
    // 结束写Record
    public void endRecord(Record r, String tag) throws IOException {
    }
    // 开始写Vector
    public void startVector(List<?> v, String tag) throws IOException {
        if (v == null) {
            writeInt(-1, tag);
            return;
        }
        writeInt(v.size(), tag);
    }
    // 结束写Vector
    public void endVector(List<?> v, String tag) throws IOException {
    }
    // 开始写Map
    public void startMap(TreeMap<?, ?> v, String tag) throws IOException {
        writeInt(v.size(), tag);
    }
    // 结束写Map
    public void endMap(TreeMap<?, ?> v, String tag) throws IOException {
    }

}
