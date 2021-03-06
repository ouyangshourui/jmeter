/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.impl.conn;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.ClientConnectionOperator;
import org.apache.http.conn.ClientConnectionRequest;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.ManagedClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.pool.ConnPoolControl;
import org.apache.http.pool.PoolStats;
import org.apache.http.util.Args;
import org.apache.http.util.Asserts;

/**
 * Copy/Paste of {@link PoolingClientConnectionManager} with a slight modification 
 * extracted from {@link PoolingHttpClientConnectionManager} to allow using 
 * better validation mechanism introduced in 4.4
 * TODO : Remove when full upgrade to new HttpClient 4.5.X API is finished
 * @deprecated Will be removed in 3.2, DO NOT USE
 */
@Deprecated
public class JMeterPoolingClientConnectionManager implements ClientConnectionManager, ConnPoolControl<HttpRoute> {

    private static final int VALIDATE_AFTER_INACTIVITY_DEFAULT = 2000;

    private final Log log = LogFactory.getLog(getClass());
    
    private final SchemeRegistry schemeRegistry;

    private final HttpConnPool pool;

    private final ClientConnectionOperator operator;
    

    /** the custom-configured DNS lookup mechanism. */
    private final DnsResolver dnsResolver;

    public JMeterPoolingClientConnectionManager(final SchemeRegistry schreg) {
        this(schreg, -1, TimeUnit.MILLISECONDS);
    }

    public JMeterPoolingClientConnectionManager(final SchemeRegistry schreg,final DnsResolver dnsResolver) {
        this(schreg, -1, TimeUnit.MILLISECONDS,dnsResolver);
    }

    public JMeterPoolingClientConnectionManager() {
        this(SchemeRegistryFactory.createDefault());
    }

    public JMeterPoolingClientConnectionManager(
            final SchemeRegistry schemeRegistry,
            final long timeToLive, final TimeUnit tunit) {
        this(schemeRegistry, timeToLive, tunit, new SystemDefaultDnsResolver());
    }
    
    public JMeterPoolingClientConnectionManager(
            final SchemeRegistry schemeRegistry,
            final long timeToLive, final TimeUnit tunit,
            final DnsResolver dnsResolver) {
        this(schemeRegistry, timeToLive, tunit, dnsResolver, VALIDATE_AFTER_INACTIVITY_DEFAULT);
    }

    public JMeterPoolingClientConnectionManager(final SchemeRegistry schemeRegistry,
                final long timeToLive, final TimeUnit tunit,
                final DnsResolver dnsResolver,
                int validateAfterInactivity) {
        super();
        Args.notNull(schemeRegistry, "Scheme registry");
        Args.notNull(dnsResolver, "DNS resolver");
        this.schemeRegistry = schemeRegistry;
        this.dnsResolver  = dnsResolver;
        this.operator = createConnectionOperator(schemeRegistry);
        this.pool = new HttpConnPool(this.log, this.operator, 2, 20, timeToLive, tunit);
        pool.setValidateAfterInactivity(validateAfterInactivity);
    }
    
    /**
     * @see #setValidateAfterInactivity(int)
     *
     * @since 4.5.2
     */
    public int getValidateAfterInactivity() {
        return pool.getValidateAfterInactivity();
    }

    /**
     * Defines period of inactivity in milliseconds after which persistent connections must
     * be re-validated prior to being {@link #leaseConnection(java.util.concurrent.Future,
     *   long, java.util.concurrent.TimeUnit) leased} to the consumer. Non-positive value passed
     * to this method disables connection validation. This check helps detect connections
     * that have become stale (half-closed) while kept inactive in the pool.
     *
     * @see #leaseConnection(java.util.concurrent.Future, long, java.util.concurrent.TimeUnit)
     *
     * @since 4.5.2
     */
    public void setValidateAfterInactivity(final int ms) {
        pool.setValidateAfterInactivity(ms);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            shutdown();
        } finally {
            super.finalize();
        }
    }

    /**
     * Hook for creating the connection operator.
     * It is called by the constructor.
     * Derived classes can override this method to change the
     * instantiation of the operator.
     * The default implementation here instantiates
     * {@link DefaultClientConnectionOperator DefaultClientConnectionOperator}.
     *
     * @param schreg    the scheme registry.
     *
     * @return  the connection operator to use
     */
    protected ClientConnectionOperator createConnectionOperator(final SchemeRegistry schreg) {
            return new DefaultClientConnectionOperator(schreg, this.dnsResolver);
    }
    @Override
    public SchemeRegistry getSchemeRegistry() {
        return this.schemeRegistry;
    }

    private String format(final HttpRoute route, final Object state) {
        final StringBuilder buf = new StringBuilder();
        buf.append("[route: ").append(route).append("]");
        if (state != null) {
            buf.append("[state: ").append(state).append("]");
        }
        return buf.toString();
    }

    private String formatStats(final HttpRoute route) {
        final StringBuilder buf = new StringBuilder();
        final PoolStats totals = this.pool.getTotalStats();
        final PoolStats stats = this.pool.getStats(route);
        buf.append("[total kept alive: ").append(totals.getAvailable()).append("; ");
        buf.append("route allocated: ").append(stats.getLeased() + stats.getAvailable());
        buf.append(" of ").append(stats.getMax()).append("; ");
        buf.append("total allocated: ").append(totals.getLeased() + totals.getAvailable());
        buf.append(" of ").append(totals.getMax()).append("]");
        return buf.toString();
    }

    private String format(final HttpPoolEntry entry) {
        final StringBuilder buf = new StringBuilder();
        buf.append("[id: ").append(entry.getId()).append("]");
        buf.append("[route: ").append(entry.getRoute()).append("]");
        final Object state = entry.getState();
        if (state != null) {
            buf.append("[state: ").append(state).append("]");
        }
        return buf.toString();
    }

    @Override
    public ClientConnectionRequest requestConnection(
            final HttpRoute route,
            final Object state) {
        Args.notNull(route, "HTTP route");
        if (this.log.isDebugEnabled()) {
            this.log.debug("Connection request: " + format(route, state) + formatStats(route));
        }
        final Future<HttpPoolEntry> future = this.pool.lease(route, state);

        return new ClientConnectionRequest() {
            @Override
            public void abortRequest() {
                future.cancel(true);
            }
            @Override
            public ManagedClientConnection getConnection(
                    final long timeout,
                    final TimeUnit tunit) throws InterruptedException, ConnectionPoolTimeoutException {
                return leaseConnection(future, timeout, tunit);
            }

        };

    }

    ManagedClientConnection leaseConnection(
            final Future<HttpPoolEntry> future,
            final long timeout,
            final TimeUnit tunit) throws InterruptedException, ConnectionPoolTimeoutException {
        final HttpPoolEntry entry;
        try {
            entry = future.get(timeout, tunit);
            if (entry == null || future.isCancelled()) {
                throw new InterruptedException();
            }
            Asserts.check(entry.getConnection() != null, "Pool entry with no connection");
            if (this.log.isDebugEnabled()) {
                this.log.debug("Connection leased: " + format(entry) + formatStats(entry.getRoute()));
            }
            return new ManagedClientConnectionImpl(this, this.operator, entry);
        } catch (final ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause == null) {
                cause = ex;
            }
            this.log.error("Unexpected exception leasing connection from pool", cause);
            // Should never happen
            throw new InterruptedException();
        } catch (final TimeoutException ex) {
            throw new ConnectionPoolTimeoutException("Timeout waiting for connection from pool");
        }
    }
    @Override
    public void releaseConnection(
            final ManagedClientConnection conn, final long keepalive, final TimeUnit tunit) {

        Args.check(conn instanceof ManagedClientConnectionImpl, "Connection class mismatch, " +
            "connection not obtained from this manager");
        final ManagedClientConnectionImpl managedConn = (ManagedClientConnectionImpl) conn;
        Asserts.check(managedConn.getManager() == this, "Connection not obtained from this manager");
        synchronized (managedConn) {
            final HttpPoolEntry entry = managedConn.detach();
            if (entry == null) {
                return;
            }
            try {
                if (managedConn.isOpen() && !managedConn.isMarkedReusable()) {
                    try {
                        managedConn.shutdown();
                    } catch (final IOException iox) {
                        if (this.log.isDebugEnabled()) {
                            this.log.debug("I/O exception shutting down released connection", iox);
                        }
                    }
                }
                // Only reusable connections can be kept alive
                if (managedConn.isMarkedReusable()) {
                    entry.updateExpiry(keepalive, tunit != null ? tunit : TimeUnit.MILLISECONDS);
                    if (this.log.isDebugEnabled()) {
                        final String s;
                        if (keepalive > 0) {
                            s = "for " + keepalive + " " + tunit;
                        } else {
                            s = "indefinitely";
                        }
                        this.log.debug("Connection " + format(entry) + " can be kept alive " + s);
                    }
                }
            } finally {
                this.pool.release(entry, managedConn.isMarkedReusable());
            }
            if (this.log.isDebugEnabled()) {
                this.log.debug("Connection released: " + format(entry) + formatStats(entry.getRoute()));
            }
        }
    }
    @Override
    public void shutdown() {
        this.log.debug("Connection manager is shutting down");
        try {
            this.pool.shutdown();
        } catch (final IOException ex) {
            this.log.debug("I/O exception shutting down connection manager", ex);
        }
        this.log.debug("Connection manager shut down");
    }
    @Override
    public void closeIdleConnections(final long idleTimeout, final TimeUnit tunit) {
        if (this.log.isDebugEnabled()) {
            this.log.debug("Closing connections idle longer than " + idleTimeout + " " + tunit);
        }
        this.pool.closeIdle(idleTimeout, tunit);
    }
    @Override
    public void closeExpiredConnections() {
        this.log.debug("Closing expired connections");
        this.pool.closeExpired();
    }
    @Override
    public int getMaxTotal() {
        return this.pool.getMaxTotal();
    }
    @Override
    public void setMaxTotal(final int max) {
        this.pool.setMaxTotal(max);
    }
    @Override
    public int getDefaultMaxPerRoute() {
        return this.pool.getDefaultMaxPerRoute();
    }
    @Override
    public void setDefaultMaxPerRoute(final int max) {
        this.pool.setDefaultMaxPerRoute(max);
    }
    @Override
    public int getMaxPerRoute(final HttpRoute route) {
        return this.pool.getMaxPerRoute(route);
    }
    @Override
    public void setMaxPerRoute(final HttpRoute route, final int max) {
        this.pool.setMaxPerRoute(route, max);
    }
    @Override
    public PoolStats getTotalStats() {
        return this.pool.getTotalStats();
    }
    @Override
    public PoolStats getStats(final HttpRoute route) {
        return this.pool.getStats(route);
    }

    /**
     * @return the dnsResolver
     */
    protected DnsResolver getDnsResolver() {
        return dnsResolver;
    }

}

