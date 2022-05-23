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

import java.io.IOException;

/**
 * Interface that all the Deserializers have to implement.
 *
 */
// Archive [ˈɑːkaɪv] 档案文件; 档案; 档案馆; 档案室;
// 所有反序列化器都需要实现的接口
public interface InputArchive {
    // 读取byte类型
    byte readByte(String tag) throws IOException;
    // 读取boolean类型
    boolean readBool(String tag) throws IOException;
    // 读取int类型
    int readInt(String tag) throws IOException;
    // 读取long类型
    long readLong(String tag) throws IOException;
    // 读取float类型
    float readFloat(String tag) throws IOException;
    // 读取double类型
    double readDouble(String tag) throws IOException;
    // 读取String类型
    String readString(String tag) throws IOException;
    // 通过缓冲方式读取
    byte[] readBuffer(String tag) throws IOException;
    // 开始读取记录
    void readRecord(Record r, String tag) throws IOException;
    // 开始读取记录
    void startRecord(String tag) throws IOException;
    // 结束读取记录
    void endRecord(String tag) throws IOException;
    // 开始读取向量
    Index startVector(String tag) throws IOException;
    // 结束读取向量
    void endVector(String tag) throws IOException;
    // 开始读取Map
    Index startMap(String tag) throws IOException;
    // 结束读取Map
    void endMap(String tag) throws IOException;

}
