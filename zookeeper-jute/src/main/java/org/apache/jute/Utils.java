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

/**
 * Various utility functions for Hadoop record I/O runtime.
 */
// Jute是Zookeeper底层序列化组件，其用于Zookeeper进行网络数据传输和本地磁盘数据存储的序列化和反序列化工作。
public class Utils {

    /**
     * Cannot create a new instance of Utils.
     */
    private Utils() {
        super();
    }

    /**
     * equals function that actually compares two buffers.
     *
     * @param onearray First buffer
     * @param twoarray Second buffer
     * @return true if one and two contain exactly the same content, else false.
     */
    public static boolean bufEquals(byte[] onearray, byte[] twoarray) {
        if (onearray == twoarray) {
            return true;
        }

        boolean ret = (onearray.length == twoarray.length);

        if (!ret) {
            return ret;
        }

        for (int idx = 0; idx < onearray.length; idx++) {
            if (onearray[idx] != twoarray[idx]) {
                return false;
            }
        }
        return true;
    }

    public static int compareBytes(byte[] b1, int off1, int len1, byte[] b2, int off2, int len2) {
        int i;
        for (i = 0; i < len1 && i < len2; i++) {
            if (b1[off1 + i] != b2[off2 + i]) {
                return b1[off1 + i] < b2[off2 + i] ? -1 : 1;
            }
        }
        if (len1 != len2) {
            return len1 < len2 ? -1 : 1;
        }
        return 0;
    }
}
