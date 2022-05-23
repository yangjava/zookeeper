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

package org.apache.zookeeper.server.persistence;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.zip.Adler32;
import java.util.zip.Checksum;
import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.ServerStats;
import org.apache.zookeeper.server.TxnLogEntry;
import org.apache.zookeeper.server.util.SerializeUtils;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the TxnLog interface. It provides api's
 * to access the txnlogs and add entries to it.
 * <p>
 * The format of a Transactional log is as follows:
 * <blockquote><pre>
 * LogFile:
 *     FileHeader TxnList ZeroPad
 *
 * FileHeader: {
 *     magic 4bytes (ZKLG)
 *     version 4bytes
 *     dbid 8bytes
 *   }
 *
 * TxnList:
 *     Txn || Txn TxnList
 *
 * Txn:
 *     checksum Txnlen TxnHeader Record 0x42
 *
 * checksum: 8bytes Adler32 is currently used
 *   calculated across payload -- Txnlen, TxnHeader, Record and 0x42
 *
 * Txnlen:
 *     len 4bytes
 *
 * TxnHeader: {
 *     sessionid 8bytes
 *     cxid 4bytes
 *     zxid 8bytes
 *     time 8bytes
 *     type 4bytes
 *   }
 *
 * Record:
 *     See Jute definition file for details on the various record types
 *
 * ZeroPad:
 *     0 padded to EOF (filled during preallocation stage)
 * </pre></blockquote>
 */
public class FileTxnLog implements TxnLog, Closeable {

    private static final Logger LOG;
    // 魔术数字，默认为1514884167
    public static final int TXNLOG_MAGIC = ByteBuffer.wrap("ZKLG".getBytes()).getInt();
    // 版本号
    public static final int VERSION = 2;

    public static final String LOG_FILE_PREFIX = "log";

    static final String FSYNC_WARNING_THRESHOLD_MS_PROPERTY = "fsync.warningthresholdms";
    static final String ZOOKEEPER_FSYNC_WARNING_THRESHOLD_MS_PROPERTY = "zookeeper." + FSYNC_WARNING_THRESHOLD_MS_PROPERTY;

    /** Maximum time we allow for elapsed fsync before WARNing */
    // 进行同步时，发出warn之前所能等待的最长时间
    private static final long fsyncWarningThresholdMS;

    /**
     * This parameter limit the size of each txnlog to a given limit (KB).
     * It does not affect how often the system will take a snapshot [zookeeper.snapCount]
     * We roll the txnlog when either of the two limits are reached.
     * Also since we only roll the logs at transaction boundaries, actual file size can exceed
     * this limit by the maximum size of a serialized transaction.
     * The feature is disabled by default (-1)
     */
    private static final String txnLogSizeLimitSetting = "zookeeper.txnLogSizeLimitInKb";

    /**
     * The actual txnlog size limit in bytes.
     */
    private static long txnLogSizeLimit = -1;
    // 静态属性，确定Logger、预分配空间大小和最长时间
    static {
        LOG = LoggerFactory.getLogger(FileTxnLog.class);

        /** Local variable to read fsync.warningthresholdms into */
        Long fsyncWarningThreshold;
        if ((fsyncWarningThreshold = Long.getLong(ZOOKEEPER_FSYNC_WARNING_THRESHOLD_MS_PROPERTY)) == null) {
            fsyncWarningThreshold = Long.getLong(FSYNC_WARNING_THRESHOLD_MS_PROPERTY, 1000);
        }
        fsyncWarningThresholdMS = fsyncWarningThreshold;

        Long logSize = Long.getLong(txnLogSizeLimitSetting, -1);
        if (logSize > 0) {
            LOG.info("{} = {}", txnLogSizeLimitSetting, logSize);

            // Convert to bytes
            logSize = logSize * 1024;
            txnLogSizeLimit = logSize;
        }
    }
    // 最大(新)的zxid
    long lastZxidSeen;
    // 存储数据相关的流
    volatile BufferedOutputStream logStream = null;
    volatile OutputArchive oa;
    volatile FileOutputStream fos = null;
    // log目录文件
    File logDir;
    // 是否强制同步
    private final boolean forceSync = !System.getProperty("zookeeper.forceSync", "yes").equals("no");
    // 数据库id
    long dbId;
    // 流列表
    private final Queue<FileOutputStream> streamsToFlush = new ArrayDeque<>();
    // 写日志文件
    File logFileWrite = null;
    private FilePadding filePadding = new FilePadding();

    private ServerStats serverStats;

    private volatile long syncElapsedMS = -1L;

    /**
     * A running total of all complete log files
     * This does not include the current file being written to
     */
    private long prevLogsRunningTotal;

    /**
     * constructor for FileTxnLog. Take the directory
     * where the txnlogs are stored
     * @param logDir the directory where the txnlogs are stored
     */
    public FileTxnLog(File logDir) {
        this.logDir = logDir;
    }

    /**
     * method to allow setting preallocate size
     * of log file to pad the file.
     * @param size the size to set to in bytes
     */
    public static void setPreallocSize(long size) {
        FilePadding.setPreallocSize(size);
    }

    /**
     * Setter for ServerStats to monitor fsync threshold exceed
     * @param serverStats used to update fsyncThresholdExceedCount
     */
    @Override
    public synchronized void setServerStats(ServerStats serverStats) {
        this.serverStats = serverStats;
    }

    /**
     * Set log size limit
     */
    public static void setTxnLogSizeLimit(long size) {
        txnLogSizeLimit = size;
    }

    /**
     * Return the current on-disk size of log size. This will be accurate only
     * after commit() is called. Otherwise, unflushed txns may not be included.
     */
    public synchronized long getCurrentLogSize() {
        if (logFileWrite != null) {
            return logFileWrite.length();
        }
        return 0;
    }

    public synchronized void setTotalLogSize(long size) {
        prevLogsRunningTotal = size;
    }

    public synchronized long getTotalLogSize() {
        return prevLogsRunningTotal + getCurrentLogSize();
    }

    /**
     * creates a checksum algorithm to be used
     * @return the checksum used for this txnlog
     */
    protected Checksum makeChecksumAlgorithm() {
        return new Adler32();
    }

    /**
     * rollover the current log file to a new one.
     * @throws IOException
     */
    public synchronized void rollLog() throws IOException {
        if (logStream != null) {
            this.logStream.flush();
            prevLogsRunningTotal += getCurrentLogSize();
            this.logStream = null;
            oa = null;

            // Roll over the current log file into the running total
        }
    }

    /**
     * close all the open file handles
     * @throws IOException
     */
    public synchronized void close() throws IOException {
        if (logStream != null) {
            logStream.close();
        }
        for (FileOutputStream log : streamsToFlush) {
            log.close();
        }
    }

    /**
     * append an entry to the transaction log
     * @param hdr the header of the transaction
     * @param txn the transaction part of the entry
     * returns true iff something appended, otw false
     */
    public synchronized boolean append(TxnHeader hdr, Record txn) throws IOException {
              return append(hdr, txn, null);
    }

    @Override
    public synchronized boolean append(TxnHeader hdr, Record txn, TxnDigest digest) throws IOException {
        // 事务头部不为空
        if (hdr == null) {
            return false;
        }
        // 事务的zxid小于等于最后的zxid
        if (hdr.getZxid() <= lastZxidSeen) {
            LOG.warn(
                "Current zxid {} is <= {} for {}",
                hdr.getZxid(),
                lastZxidSeen,
                hdr.getType());
        } else {
            lastZxidSeen = hdr.getZxid();
        }
        // 日志流为空
        if (logStream == null) {
            LOG.info("Creating new log file: {}", Util.makeLogName(hdr.getZxid()));

            logFileWrite = new File(logDir, Util.makeLogName(hdr.getZxid()));
            fos = new FileOutputStream(logFileWrite);
            logStream = new BufferedOutputStream(fos);
            oa = BinaryOutputArchive.getArchive(logStream);
            FileHeader fhdr = new FileHeader(TXNLOG_MAGIC, VERSION, dbId);
            // 序列化
            fhdr.serialize(oa, "fileheader");
            // Make sure that the magic number is written before padding.
            // 刷新到磁盘
            logStream.flush();
            // 当前通道的大小
            filePadding.setCurrentSize(fos.getChannel().position());
            // 添加fos
            streamsToFlush.add(fos);
        }
        // 填充文件
        filePadding.padFile(fos.getChannel());
        // 将事务头和事务数据序列化成Byte Buffer
        byte[] buf = Util.marshallTxnEntry(hdr, txn, digest);
        if (buf == null || buf.length == 0) {
            throw new IOException("Faulty serialization for header " + "and txn");
        }
        // 生成一个验证算法
        Checksum crc = makeChecksumAlgorithm();
        // 使用Byte数组来更新当前的Checksum
        crc.update(buf, 0, buf.length);
        // 写long类型数据
        oa.writeLong(crc.getValue(), "txnEntryCRC");
        // 将序列化的事务记录写入OutputArchive
        Util.writeTxnBytes(oa, buf);

        return true;
    }

    /**
     * Find the log file that starts at, or just before, the snapshot. Return
     * this and all subsequent logs. Results are ordered by zxid of file,
     * ascending order.
     * @param logDirList array of files
     * @param snapshotZxid return files at, or before this zxid
     * @return
     */
    // 该函数的作用是找出刚刚小于或者等于snapshot的所有log文件。
    public static File[] getLogFiles(File[] logDirList, long snapshotZxid) {
        // 按照zxid对文件进行排序
        List<File> files = Util.sortDataDir(logDirList, LOG_FILE_PREFIX, true);
        long logZxid = 0;
        // Find the log file that starts before or at the same time as the
        // zxid of the snapshot
        // 遍历文件
        for (File f : files) {
            // 从文件中获取zxid
            long fzxid = Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX);
            // 跳过大于snapshotZxid的文件
            if (fzxid > snapshotZxid) {
                break;
            }
            // the files
            // are sorted with zxid's
            // 找出文件中最大的zxid(同时还需要小于等于snapshotZxid)
            if (fzxid > logZxid) {
                logZxid = fzxid;
            }
        }
        // 文件列表
        List<File> v = new ArrayList<File>(5);
        // 再次遍历文件
        for (File f : files) {
            // 从文件中获取zxid
            long fzxid = Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX);
            // 跳过小于logZxid的文件
            if (fzxid < logZxid) {
                continue;
            }
            v.add(f);
        }
        // 转化成File[] 类型后返回
        return v.toArray(new File[0]);

    }

    /**
     * get the last zxid that was logged in the transaction logs
     * @return the last zxid logged in the transaction logs
     */
    // 该函数主要用于获取记录在log中的最后一个zxid。
    public long getLastLoggedZxid() {
        // 获取已排好序的所有的log文件
        File[] files = getLogFiles(logDir.listFiles(), 0);
        // 获取最大的zxid(最后一个log文件对应的zxid)
        long maxLog = files.length > 0 ? Util.getZxidFromName(files[files.length - 1].getName(), LOG_FILE_PREFIX) : -1;

        // if a log file is more recent we must scan it to find
        // the highest zxid
        long zxid = maxLog;
        // 迭代器
        TxnIterator itr = null;
        try {
            // 新生FileTxnLog
            FileTxnLog txn = new FileTxnLog(logDir);
            // 开始读取从给定zxid之后的所有事务
            itr = txn.read(maxLog);
            // 遍历
            while (true) {
                // 是否存在下一项
                if (!itr.next()) {
                    break;
                }
                // 获取事务头
                TxnHeader hdr = itr.getHeader();
                // 获取zxid
                zxid = hdr.getZxid();
            }
        } catch (IOException e) {
            LOG.warn("Unexpected exception", e);
        } finally {
            // 关闭迭代器
            close(itr);
        }
        return zxid;
    }

    private void close(TxnIterator itr) {
        if (itr != null) {
            try {
                itr.close();
            } catch (IOException ioe) {
                LOG.warn("Error closing file iterator", ioe);
            }
        }
    }

    /**
     * commit the logs. make sure that everything hits the
     * disk
     */
    public synchronized void commit() throws IOException {
        if (logStream != null) {
            // 强制刷到磁盘
            logStream.flush();
        }
        // 遍历流
        for (FileOutputStream log : streamsToFlush) {
            // 强制刷到磁盘
            log.flush();
            // 是否强制同步
            if (forceSync) {
                long startSyncNS = System.nanoTime();

                FileChannel channel = log.getChannel();
                channel.force(false);
                // 计算流式的时间
                syncElapsedMS = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startSyncNS);
                // 大于阈值时则会警告
                if (syncElapsedMS > fsyncWarningThresholdMS) {
                    if (serverStats != null) {
                        serverStats.incrementFsyncThresholdExceedCount();
                    }

                    LOG.warn(
                        "fsync-ing the write ahead log in {} took {}ms which will adversely effect operation latency."
                            + "File size is {} bytes. See the ZooKeeper troubleshooting guide",
                        Thread.currentThread().getName(),
                        syncElapsedMS,
                        channel.size());
                }

                ServerMetrics.getMetrics().FSYNC_TIME.add(syncElapsedMS);
            }
        }
        // 移除流并关闭
        while (streamsToFlush.size() > 1) {
            streamsToFlush.poll().close();
        }

        // Roll the log file if we exceed the size limit
        if (txnLogSizeLimit > 0) {
            long logSize = getCurrentLogSize();

            if (logSize > txnLogSizeLimit) {
                LOG.debug("Log size limit reached: {}", logSize);
                rollLog();
            }
        }
    }

    /**
     *
     * @return elapsed sync time of transaction log in milliseconds
     */
    public long getTxnLogSyncElapsedTime() {
        return syncElapsedMS;
    }

    /**
     * start reading all the transactions from the given zxid
     * @param zxid the zxid to start reading transactions from
     * @return returns an iterator to iterate through the transaction
     * logs
     */
    // 返回事务文件访问迭代器
    public TxnIterator read(long zxid) throws IOException {
        return read(zxid, true);
    }

    /**
     * start reading all the transactions from the given zxid.
     *
     * @param zxid the zxid to start reading transactions from
     * @param fastForward true if the iterator should be fast forwarded to point
     *        to the txn of a given zxid, else the iterator will point to the
     *        starting txn of a txnlog that may contain txn of a given zxid
     * @return returns an iterator to iterate through the transaction logs
     */
    public TxnIterator read(long zxid, boolean fastForward) throws IOException {
        return new FileTxnIterator(logDir, zxid, fastForward);
    }

    /**
     * truncate the current transaction logs
     * @param zxid the zxid to truncate the logs to
     * @return true if successful false if not
     */
    //
    public boolean truncate(long zxid) throws IOException {
        FileTxnIterator itr = null;
        try {
            // 获取迭代器
            itr = new FileTxnIterator(this.logDir, zxid);
            PositionInputStream input = itr.inputStream;
            if (input == null) {
                throw new IOException("No log files found to truncate! This could "
                                      + "happen if you still have snapshots from an old setup or "
                                      + "log files were deleted accidentally or dataLogDir was changed in zoo.cfg.");
            }
            long pos = input.getPosition();
            // now, truncate at the current position
            // 从当前位置开始清空
            RandomAccessFile raf = new RandomAccessFile(itr.logFile, "rw");
            raf.setLength(pos);
            raf.close();
            // 存在下一个log文件
            while (itr.goToNextLog()) {
                // 删除
                if (!itr.logFile.delete()) {
                    LOG.warn("Unable to truncate {}", itr.logFile);
                }
            }
        } finally {
            // 关闭迭代器
            close(itr);
        }
        return true;
    }

    /**
     * read the header of the transaction file
     * @param file the transaction file to read
     * @return header that was read from the file
     * @throws IOException
     */
    private static FileHeader readHeader(File file) throws IOException {
        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(file));
            InputArchive ia = BinaryInputArchive.getArchive(is);
            FileHeader hdr = new FileHeader();
            hdr.deserialize(ia, "fileheader");
            return hdr;
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
                LOG.warn("Ignoring exception during close", e);
            }
        }
    }

    /**
     * the dbid of this transaction database
     * @return the dbid of this database
     */
    public long getDbId() throws IOException {
        FileTxnIterator itr = new FileTxnIterator(logDir, 0);
        FileHeader fh = readHeader(itr.logFile);
        itr.close();
        if (fh == null) {
            throw new IOException("Unsupported Format.");
        }
        return fh.getDbid();
    }

    /**
     * the forceSync value. true if forceSync is enabled, false otherwise.
     * @return the forceSync value
     */
    public boolean isForceSync() {
        return forceSync;
    }

    /**
     * a class that keeps track of the position
     * in the input stream. The position points to offset
     * that has been consumed by the applications. It can
     * wrap buffered input streams to provide the right offset
     * for the application.
     */
    static class PositionInputStream extends FilterInputStream {

        long position;
        protected PositionInputStream(InputStream in) {
            super(in);
            position = 0;
        }

        @Override
        public int read() throws IOException {
            int rc = super.read();
            if (rc > -1) {
                position++;
            }
            return rc;
        }

        public int read(byte[] b) throws IOException {
            int rc = super.read(b);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int rc = super.read(b, off, len);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }

        @Override
        public long skip(long n) throws IOException {
            long rc = super.skip(n);
            if (rc > 0) {
                position += rc;
            }
            return rc;
        }
        public long getPosition() {
            return position;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void mark(int readLimit) {
            throw new UnsupportedOperationException("mark");
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException("reset");
        }

    }

    /**
     * this class implements the txnlog iterator interface
     * which is used for reading the transaction logs
     */
    public static class FileTxnIterator implements TxnLog.TxnIterator {

        File logDir;
        long zxid;
        TxnHeader hdr;
        Record record;
        TxnDigest digest;
        File logFile;
        InputArchive ia;
        static final String CRC_ERROR = "CRC check failed";

        PositionInputStream inputStream = null;
        //stored files is the list of files greater than
        //the zxid we are looking for.
        private ArrayList<File> storedFiles;

        /**
         * create an iterator over a transaction database directory
         * @param logDir the transaction database directory
         * @param zxid the zxid to start reading from
         * @param fastForward   true if the iterator should be fast forwarded to
         *        point to the txn of a given zxid, else the iterator will
         *        point to the starting txn of a txnlog that may contain txn of
         *        a given zxid
         * @throws IOException
         */
        public FileTxnIterator(File logDir, long zxid, boolean fastForward) throws IOException {
            this.logDir = logDir;
            this.zxid = zxid;
            init();

            if (fastForward && hdr != null) {
                while (hdr.getZxid() < zxid) {
                    if (!next()) {
                        break;
                    }
                }
            }
        }

        /**
         * create an iterator over a transaction database directory
         * @param logDir the transaction database directory
         * @param zxid the zxid to start reading from
         * @throws IOException
         */
        public FileTxnIterator(File logDir, long zxid) throws IOException {
            this(logDir, zxid, true);
        }

        /**
         * initialize to the zxid specified
         * this is inclusive of the zxid
         * @throws IOException
         */
        //
        void init() throws IOException {
            // 新生成文件列表
            storedFiles = new ArrayList<>();
            // 进行排序
            List<File> files = Util.sortDataDir(
                FileTxnLog.getLogFiles(logDir.listFiles(), 0),
                LOG_FILE_PREFIX,
                false);
            // 遍历文件
            for (File f : files) {
                // 添加zxid大于等于指定zxid的文件
                if (Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX) >= zxid) {
                    storedFiles.add(f);
               // 只添加一个zxid小于指定zxid的文件，然后退出
                } else if (Util.getZxidFromName(f.getName(), LOG_FILE_PREFIX) < zxid) {
                    // add the last logfile that is less than the zxid
                    storedFiles.add(f);
                    break;
                }
            }
            // 进入下一个log文件
            goToNextLog();
            next();
        }

        /**
         * Return total storage size of txnlog that will return by this iterator.
         */
        public long getStorageSize() {
            long sum = 0;
            for (File f : storedFiles) {
                sum += f.length();
            }
            return sum;
        }

        /**
         * go to the next logfile
         * @return true if there is one and false if there is no
         * new file to be read
         * @throws IOException
         */
        // goToNextLog表示选取下一个log文件
        private boolean goToNextLog() throws IOException {
            // 存储的文件列表大于0
            if (storedFiles.size() > 0) {
                // 取最后一个log文件
                this.logFile = storedFiles.remove(storedFiles.size() - 1);
                // 针对该文件，创建InputArchive
                ia = createInputArchive(this.logFile);
                // 返回true
                return true;
            }
            return false;
        }

        /**
         * read the header from the inputarchive
         * @param ia the inputarchive to be read from
         * @param is the inputstream
         * @throws IOException
         */
        protected void inStreamCreated(InputArchive ia, InputStream is) throws IOException {
            FileHeader header = new FileHeader();
            header.deserialize(ia, "fileheader");
            if (header.getMagic() != FileTxnLog.TXNLOG_MAGIC) {
                throw new IOException("Transaction log: " + this.logFile
                                      + " has invalid magic number "
                                      + header.getMagic() + " != " + FileTxnLog.TXNLOG_MAGIC);
            }
        }

        /**
         * Invoked to indicate that the input stream has been created.
         * @param logFile the file to read.
         * @throws IOException
         **/
        protected InputArchive createInputArchive(File logFile) throws IOException {
            if (inputStream == null) {
                inputStream = new PositionInputStream(new BufferedInputStream(new FileInputStream(logFile)));
                LOG.debug("Created new input stream: {}", logFile);
                ia = BinaryInputArchive.getArchive(inputStream);
                inStreamCreated(ia, inputStream);
                LOG.debug("Created new input archive: {}", logFile);
            }
            return ia;
        }

        /**
         * create a checksum algorithm
         * @return the checksum algorithm
         */
        protected Checksum makeChecksumAlgorithm() {
            return new Adler32();
        }

        /**
         * the iterator that moves to the next transaction
         * @return true if there is more transactions to be read
         * false if not.
         */
        public boolean next() throws IOException {
            // 为空，返回false
            if (ia == null) {
                return false;
            }
            try {
                // 读取长整形crcValue
                long crcValue = ia.readLong("crcvalue");
                // 通过input archive读取一个事务条目
                byte[] bytes = Util.readTxnBytes(ia);
                // Since we preallocate, we define EOF to be an
                // 对bytes进行判断
                if (bytes == null || bytes.length == 0) {
                    throw new EOFException("Failed to read " + logFile);
                }
                // EOF or corrupted record
                // validate CRC
                // 验证CRC
                Checksum crc = makeChecksumAlgorithm();
                // 更新
                crc.update(bytes, 0, bytes.length);
                // 验证不相等，抛出异常
                if (crcValue != crc.getValue()) {
                    throw new IOException(CRC_ERROR);
                }
                TxnLogEntry logEntry = SerializeUtils.deserializeTxn(bytes);
                // 新生成TxnHeader
                hdr = logEntry.getHeader();
                record = logEntry.getTxn();
                digest = logEntry.getDigest();
            } catch (EOFException e) {
                // 抛出异常
                LOG.debug("EOF exception", e);
                inputStream.close();
                inputStream = null;
                ia = null;
                hdr = null;
                // this means that the file has ended
                // we should go to the next file
                // 没有log文件，则返回false
                if (!goToNextLog()) {
                    return false;
                }
                // if we went to the next log file, we should call next() again
                // 继续调用next
                return next();
            } catch (IOException e) {
                inputStream.close();
                throw e;
            }
            return true;
        }

        /**
         * return the current header
         * @return the current header that
         * is read
         */
        public TxnHeader getHeader() {
            return hdr;
        }

        /**
         * return the current transaction
         * @return the current transaction
         * that is read
         */
        public Record getTxn() {
            return record;
        }

        public TxnDigest getDigest() {
            return digest;
        }

        /**
         * close the iterator
         * and release the resources.
         */
        public void close() throws IOException {
            if (inputStream != null) {
                inputStream.close();
            }
        }

    }

}
