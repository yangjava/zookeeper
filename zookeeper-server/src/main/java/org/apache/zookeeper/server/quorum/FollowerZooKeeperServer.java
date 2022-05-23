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

package org.apache.zookeeper.server.quorum;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.management.JMException;
import org.apache.jute.Record;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.metrics.MetricsContext;
import org.apache.zookeeper.server.ExitCode;
import org.apache.zookeeper.server.FinalRequestProcessor;
import org.apache.zookeeper.server.Request;
import org.apache.zookeeper.server.RequestProcessor;
import org.apache.zookeeper.server.ServerMetrics;
import org.apache.zookeeper.server.SyncRequestProcessor;
import org.apache.zookeeper.server.ZKDatabase;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.txn.TxnDigest;
import org.apache.zookeeper.txn.TxnHeader;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Just like the standard ZooKeeperServer. We just replace the request
 * processors: FollowerRequestProcessor -&gt; CommitProcessor -&gt;
 * FinalRequestProcessor
 *
 * A SyncRequestProcessor is also spawned off to log proposals from the leader.
 */
// 其继承LearnerZooKeeperServer抽象类，角色为Follower。
// 其请求处理链为FollowerRequestProcessor -> CommitProcessor -> FinalRequestProcessor。
// 其核心是对待同步请求和待处理事务请求交由不同的请求处理器进行处理。
public class FollowerZooKeeperServer extends LearnerZooKeeperServer {
    // FollowerZooKeeperServer中维护着提交请求处理器和同步请求处理器，并且维护了所有待同步请求队列和待处理的事务请求队列。
    private static final Logger LOG = LoggerFactory.getLogger(FollowerZooKeeperServer.class);

    /*
     * Pending sync requests
     */
    // 待同步请求
    ConcurrentLinkedQueue<Request> pendingSyncs;

    /**
     * @throws IOException
     */
    // 其首先调用父类的构造函数，然后初始化pendingSyncs为空队列。
    FollowerZooKeeperServer(FileTxnSnapLog logFactory, QuorumPeer self, ZKDatabase zkDb) throws IOException {
        super(logFactory, self.tickTime, self.minSessionTimeout, self.maxSessionTimeout, self.clientPortListenBacklog, zkDb, self);
        // 初始化pendingSyncs
        this.pendingSyncs = new ConcurrentLinkedQueue<Request>();
    }

    public Follower getFollower() {
        return self.follower;
    }

    @Override
    protected void setupRequestProcessors() {
        RequestProcessor finalProcessor = new FinalRequestProcessor(this);
        commitProcessor = new CommitProcessor(finalProcessor, Long.toString(getServerId()), true, getZooKeeperServerListener());
        commitProcessor.start();
        firstProcessor = new FollowerRequestProcessor(this, commitProcessor);
        ((FollowerRequestProcessor) firstProcessor).start();
        syncProcessor = new SyncRequestProcessor(this, new SendAckRequestProcessor(getFollower()));
        syncProcessor.start();
    }
    // 待处理的事务请求
    LinkedBlockingQueue<Request> pendingTxns = new LinkedBlockingQueue<Request>();
    // 该函数将请求进行记录（放入到对应的队列中），等待处理。
    public void logRequest(TxnHeader hdr, Record txn, TxnDigest digest) {
        // 创建请求
        Request request = new Request(hdr.getClientId(), hdr.getCxid(), hdr.getType(), hdr, txn, hdr.getZxid());
        // 赋值请求头、事务体、zxid
        request.setTxnDigest(digest);
        // zxid不为0，表示本服务器已经处理过请求
        if ((request.zxid & 0xffffffffL) != 0) {
            // 则需要将该请求放入pendingTxns中
            pendingTxns.add(request);
        }
        // 使用SyncRequestProcessor处理请求(其会将请求放在队列中，异步进行处理)
        syncProcessor.processRequest(request);
    }

    /**
     * When a COMMIT message is received, eventually this method is called,
     * which matches up the zxid from the COMMIT with (hopefully) the head of
     * the pendingTxns queue and hands it to the commitProcessor to commit.
     * @param zxid - must correspond to the head of pendingTxns if it exists
     */
    // 该函数会提交zxid对应的请求（pendingTxns的队首元素），
    // 其首先会判断队首请求对应的zxid是否为传入的zxid，
    // 然后再进行移除和提交（放在committedRequests队列中）。
    public void commit(long zxid) {
        // 没有还在等待处理的事务
        if (pendingTxns.size() == 0) {
            LOG.warn("Committing " + Long.toHexString(zxid) + " without seeing txn");
            return;
        }
        // 队首元素的zxid
        long firstElementZxid = pendingTxns.element().zxid;
        if (firstElementZxid != zxid) {
            // 如果队首元素的zxid不等于需要提交的zxid，则退出程序
            LOG.error("Committing zxid 0x" + Long.toHexString(zxid)
                      + " but next pending txn 0x" + Long.toHexString(firstElementZxid));
            ServiceUtils.requestSystemExit(ExitCode.UNMATCHED_TXN_COMMIT.getValue());
        }
        // 从待处理事务请求队列中移除队首请求
        Request request = pendingTxns.remove();
        request.logLatency(ServerMetrics.getMetrics().COMMIT_PROPAGATION_LATENCY);
        // 提交该请求
        commitProcessor.commit(request);
    }
    // 该函数会将待同步请求队列中的元素进行提交，也是将该请求放入committedRequests队列中。
    public synchronized void sync() {
        // 没有需要同步的请求
        if (pendingSyncs.size() == 0) {
            LOG.warn("Not expecting a sync.");
            return;
        }
        // 从待同步队列中移除队首请求
        Request r = pendingSyncs.remove();
        if (r instanceof LearnerSyncRequest) {
            LearnerSyncRequest lsr = (LearnerSyncRequest) r;
            lsr.fh.queuePacket(new QuorumPacket(Leader.SYNC, 0, null, null));
        }
        // 提交该请求
        commitProcessor.commit(r);
    }

    @Override
    public int getGlobalOutstandingLimit() {
        int divisor = self.getQuorumSize() > 2 ? self.getQuorumSize() - 1 : 1;
        int globalOutstandingLimit = super.getGlobalOutstandingLimit() / divisor;
        return globalOutstandingLimit;
    }

    @Override
    public String getState() {
        return "follower";
    }

    @Override
    public Learner getLearner() {
        return getFollower();
    }

    /**
     * Process a request received from external Learner through the LearnerMaster
     * These requests have already passed through validation and checks for
     * session upgrade and can be injected into the middle of the pipeline.
     *
     * @param request received from external Learner
     */
    void processObserverRequest(Request request) {
        ((FollowerRequestProcessor) firstProcessor).processRequest(request, false);
    }

    boolean registerJMX(LearnerHandlerBean handlerBean) {
        try {
            MBeanRegistry.getInstance().register(handlerBean, jmxServerBean);
            return true;
        } catch (JMException e) {
            LOG.warn("Could not register connection", e);
        }
        return false;
    }

    @Override
    protected void registerMetrics() {
        super.registerMetrics();

        MetricsContext rootContext = ServerMetrics.getMetrics().getMetricsProvider().getRootContext();

        rootContext.registerGauge("synced_observers", self::getSynced_observers_metric);

    }

    @Override
    protected void unregisterMetrics() {
        super.unregisterMetrics();

        MetricsContext rootContext = ServerMetrics.getMetrics().getMetricsProvider().getRootContext();
        rootContext.unregisterGauge("synced_observers");

    }

}
