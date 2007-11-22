/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.mina.transport.socket.apr;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import org.apache.mina.common.AbstractPollingIoConnector;
import org.apache.mina.common.ExceptionMonitor;
import org.apache.mina.common.IoProcessor;
import org.apache.mina.common.RuntimeIoException;
import org.apache.mina.common.TransportMetadata;
import org.apache.mina.transport.socket.DefaultSocketSessionConfig;
import org.apache.mina.transport.socket.SocketConnector;
import org.apache.mina.transport.socket.SocketSessionConfig;
import org.apache.mina.util.CircularQueue;
import org.apache.tomcat.jni.Address;
import org.apache.tomcat.jni.Poll;
import org.apache.tomcat.jni.Pool;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.jni.Status;

/**
 * @author The Apache MINA Project (dev@mina.apache.org)
 * @version $Rev$, $Date$
 */
public final class AprSocketConnector extends AbstractPollingIoConnector<AprSession, Long> implements SocketConnector {

    private static final int POLLSET_SIZE = 1024;

    private final Map<Long, ConnectionRequest> requests =
        new HashMap<Long, ConnectionRequest>(POLLSET_SIZE);
    
    private final Object wakeupLock = new Object();
    private long wakeupSocket;
    private volatile boolean toBeWakenUp;

    private volatile long pool;
    private volatile long pollset; // socket poller
    private final long[] polledSockets = new long[POLLSET_SIZE << 1];
    private final List<Long> polledHandles = new CircularQueue<Long>(POLLSET_SIZE);
    private final Set<Long> failedHandles = new HashSet<Long>(POLLSET_SIZE);
    private volatile ByteBuffer dummyBuffer;

    public AprSocketConnector() {
        super(new DefaultSocketSessionConfig(), AprIoProcessor.class);
    }
    
    public AprSocketConnector(int processorCount) {
        super(new DefaultSocketSessionConfig(), AprIoProcessor.class, processorCount);
    }
    
    public AprSocketConnector(IoProcessor<AprSession> processor) {
        super(new DefaultSocketSessionConfig(), processor);
    }

    public AprSocketConnector(Executor executor, IoProcessor<AprSession> processor) {
        super(new DefaultSocketSessionConfig(), executor, processor);
    }
    
    @Override
    protected void init() {
        try {
            wakeupSocket = Socket.create(
                    Socket.APR_INET, Socket.SOCK_DGRAM, Socket.APR_PROTO_UDP, AprLibrary
                    .getInstance().getRootPool());
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeIoException("Failed to create a wakeup socket.", e);
        }

        // initialize a memory pool for APR functions
        pool = Pool.create(AprLibrary.getInstance().getRootPool());
        dummyBuffer = Pool.alloc(pool, 1);

        boolean success = false;
        try {
            pollset = Poll.create(
                            POLLSET_SIZE,
                            pool,
                            Poll.APR_POLLSET_THREADSAFE,
                            Long.MAX_VALUE);
            
            if (pollset == 0) {
                pollset = Poll.create(
                        62,
                        pool,
                        Poll.APR_POLLSET_THREADSAFE,
                        Long.MAX_VALUE);
            }

            if (pollset < 0) {
                if (Status.APR_STATUS_IS_ENOTIMPL(- (int) pollset)) {
                    throw new RuntimeIoException(
                            "Thread-safe pollset is not supported in this platform.");
                }
            }
            success = true;
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeIoException("Failed to create a pollset.", e);
        } finally {
            if (!success) {
                dispose();
            }
        }
    }

    @Override
    protected void destroy() {
        Socket.close(wakeupSocket);
        Poll.destroy(pollset);
        Pool.destroy(pool);
    }

    @Override
    protected Iterator<Long> allHandles() {
        return polledHandles.iterator();
    }

    @Override
    protected boolean connect(Long handle, SocketAddress remoteAddress)
            throws Exception {
        InetSocketAddress ra = (InetSocketAddress) remoteAddress;
        long sa;
        if (ra != null) {
            if (ra.getAddress() == null) {
                sa = Address.info(Address.APR_ANYADDR, Socket.APR_INET, ra.getPort(), 0, pool);
            } else {
                sa = Address.info(ra.getAddress().getHostAddress(), Socket.APR_INET, ra.getPort(), 0, pool);
            }
        } else {
            sa = Address.info(Address.APR_ANYADDR, Socket.APR_INET, 0, 0, pool);
        }
        
        int rv = Socket.connect(handle, sa);
        if (rv == Status.APR_SUCCESS) {
            return true;
        }
        
        if (Status.APR_STATUS_IS_EINPROGRESS(rv)) {
            return false;
        }

        throwException(rv);
        throw new InternalError(); // This sentence will never be executed.
    }

    @Override
    protected ConnectionRequest connectionRequest(Long handle) {
        return requests.get(handle);
    }

    @Override
    protected void close(Long handle) throws Exception {
        finishConnect(handle);
        int rv = Socket.close(handle);
        if (rv != Status.APR_SUCCESS) {
            throwException(rv);
        }
    }

    @Override
    protected void finishConnect(Long handle) throws Exception {
        Poll.remove(pollset, handle);
        requests.remove(handle);
        if (failedHandles.remove(handle)) {
            int rv = Socket.recvb(handle, dummyBuffer, 0, 1);
            throwException(rv);
        }
    }

    @Override
    protected Long newHandle(SocketAddress localAddress) throws Exception {
        long handle = Socket.create(
                Socket.APR_INET, Socket.SOCK_STREAM, Socket.APR_PROTO_TCP, pool);
        boolean success = false;
        try {
            Socket.optSet(handle, Socket.APR_SO_REUSEADDR, 1);
            
            if (localAddress != null) {
                InetSocketAddress la = (InetSocketAddress) localAddress;
                long sa;
                if (la != null) {
                    if (la.getAddress() == null) {
                        sa = Address.info(Address.APR_ANYADDR, Socket.APR_INET, la.getPort(), 0, pool);
                    } else {
                        sa = Address.info(la.getAddress().getHostAddress(), Socket.APR_INET, la.getPort(), 0, pool);
                    }
                } else {
                    sa = Address.info(Address.APR_ANYADDR, Socket.APR_INET, 0, 0, pool);
                }
                
                int result = Socket.bind(handle, sa);
                if (result != Status.APR_SUCCESS) {
                    throwException(result);
                }            
            }
            
            Socket.optSet(handle, Socket.APR_SO_NONBLOCK, 1);
            success = true;
            return handle;
        } finally {
            if (!success) {
                int rv = Socket.close(handle);
                if (rv != Status.APR_SUCCESS) {
                    throwException(rv);
                }
            }
        }
    }

    @Override
    protected AprSession newSession(IoProcessor<AprSession> processor,
            Long handle) {
        try {
            return new AprSocketSession(this, processor, handle);
        } catch (Exception e) {
            ExceptionMonitor.getInstance().exceptionCaught(e);
        }
        return null;
    }

    @Override
    protected void register(Long handle, ConnectionRequest request)
            throws Exception {
        int rv = Poll.add(pollset, handle, Poll.APR_POLLOUT);
        if (rv != Status.APR_SUCCESS) {
            throwException(rv);
        }
        
        requests.put(handle, request);
    }

    @Override
    protected boolean select(int timeout) throws Exception {
        int rv = Poll.poll(pollset, timeout * 1000, polledSockets, false);
        if (rv <= 0) {
            if (rv != -120001) {
                throwException(rv);
            }
            
            rv = Poll.maintain(pollset, polledSockets, true);
            if (rv > 0) {
                for (int i = 0; i < rv; i ++) {
                    Poll.add(pollset, polledSockets[i], Poll.APR_POLLOUT);
                }
            } else if (rv < 0) {
                throwException(rv);
            }
            
            return false;
        } else {
            rv <<= 1;
            if (!polledHandles.isEmpty()) {
                polledHandles.clear();
            }

            for (int i = 0; i < rv; i ++) {
                long flag = polledSockets[i];
                long socket = polledSockets[++i];
                if (socket == wakeupSocket) {
                    synchronized (wakeupLock) {
                        Poll.remove(pollset, wakeupSocket);
                        toBeWakenUp = false;
                    }
                    continue;
                }
                polledHandles.add(socket);
                if ((flag & Poll.APR_POLLOUT) == 0) {
                    failedHandles.add(socket);
                }
            }
            return !polledHandles.isEmpty();
        }
    }

    @Override
    protected Iterator<Long> selectedHandles() {
        return polledHandles.iterator();
    }

    @Override
    protected void wakeup() {
        if (toBeWakenUp) {
            return;
        }
        
        // Add a dummy socket to the pollset.
        synchronized (wakeupLock) {
            toBeWakenUp = true;
            Poll.add(pollset, wakeupSocket, Poll.APR_POLLOUT);
        }
    }

    public TransportMetadata getTransportMetadata() {
        return AprSocketSession.METADATA;
    }
    
    @Override
    public SocketSessionConfig getSessionConfig() {
        return (SocketSessionConfig) super.getSessionConfig();
    }

    private void throwException(int code) throws IOException {
        throw new IOException(
                org.apache.tomcat.jni.Error.strerror(-code) +
                " (code: " + code + ")");
    }
}