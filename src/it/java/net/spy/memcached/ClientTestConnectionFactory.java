/**
 * Copyright (C) 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved. 
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package net.spy.memcached;

import javax.net.ssl.SSLContext;

public class ClientTestConnectionFactory extends DefaultConnectionFactory {

    /**
     * Create a ClientTestConnectionFactory with the default parameters.
     */
    public ClientTestConnectionFactory() {
        super();
    }

    /**
     * Create a ClientTestConnectionFactory with the given parameters
     * 
     * @param len the queue length.
     * @param bufSize the buffer size
     */
    public ClientTestConnectionFactory(int len, int bufSize) {
        super(len, bufSize);
    }

    @Override
    public ClientMode getClientMode() {
      return TestConfig.getInstance().getClientMode();
    }
    
    @Override
    public long getOperationTimeout() {
      return 15000;
    }

    @Override
    public FailureMode getFailureMode() {
      return FailureMode.Retry;
    }
    
    @Override
    public long getDynamicModePollingInterval(){
      return 3000l;
    }
    
    @Override
    public SSLContext getSSLContext() {
      return TestConfig.getInstance().getSSLContext();
    }

    @Override
    public boolean skipTlsHostnameVerification() {
      return TestConfig.getInstance().skipTlsHostnameVerification();
    } 
}