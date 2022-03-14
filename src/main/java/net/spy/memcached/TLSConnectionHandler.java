/**
 * Copyright (C) 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved. 
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package net.spy.memcached;

import net.spy.memcached.compat.log.Logger;
import net.spy.memcached.compat.log.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;

public class TLSConnectionHandler {

  /**
  * The ByteBuffer holds this peer's application data in plaintext.
  */
  protected ByteBuffer myAppData;
    
  /**
  * The ByteBuffer holds this peer's TLS encrypted data.
  */
  protected ByteBuffer myNetData;
    
  /**
  * The ByteBuffer holds the other peer's application data in plaintext.
  */
  protected ByteBuffer peerAppData;
    
  /**
  * The ByteBuffer holds other peer's TLS encrypted data.
  */
  protected ByteBuffer peerNetData;
      
  /**
  * A selectable channel for stream-oriented connecting sockets.
  */
  private SocketChannel socketChannel;

  /**
  * For enabling secure communications using protocols such as SSL/TLS. This contains the SSL Engine if the client is TLS enabled.
  */
  private SSLEngine sslEngine;
  
  /**
  * An SSLEngineResult enum describing the current handshaking state of the SSLEngine.
  */
  private SSLEngineResult.HandshakeStatus handshakeStatus;

  private Logger log;
    
  public TLSConnectionHandler(SocketChannel socketChannel, SSLEngine sslEngine){
    log = LoggerFactory.getLogger(TLSConnectionHandler.class);

    this.socketChannel = socketChannel;
    this.sslEngine = sslEngine;

  }

  /** 
  * Initial handshake (for establishing cipher suite and key exchange between client and server).
  * @param timeoutInMillis the amount of time to wait for TLS handshake finish before timeout.
  * @return true if handshake is successful, false if handshake is unsuccessful.
  * @throws IOException if there is an error occurred during read/write to the socket channel.
  * @throws SSLException if a problem was encountered while signaling the SSLEngine to begin a new handshake
  *           or if a problem was encountered while processing the data that caused the SSLEngine to abort.
  * @throws IllegalStateException if the client/server mode has not yet been set.
  * @throws IllegalArgumentException if the handshake status is invalid.
  * @throws OperationTimeoutException if global operation timeout is exceeded
  */ 
  public boolean doTlsHandshake(long timeoutInMillis) throws IOException {

    // Create a new SSLEngine session
    SSLSession session = sslEngine.getSession();
      
    // Get the current size of largest SSL/TLS packet that is expected when using this session.
    int packetBufferSize = session.getPacketBufferSize();
    myNetData = ByteBuffer.allocate(packetBufferSize);
    peerNetData = ByteBuffer.allocate(packetBufferSize);
    
    // Get the current size of largest SSL/TLS application that is expected when using this session.
    int appBufferSize = session.getApplicationBufferSize();
    myAppData = ByteBuffer.allocate(appBufferSize);
    peerAppData = ByteBuffer.allocate(appBufferSize);

    // Prepare buffer for use
    myNetData.clear();
    peerNetData.clear();
    myAppData.clear();
    peerAppData.clear();

    // Set the start time before starting TLS handshake.
    long startTimeDoTlsHandshake = System.nanoTime();
    
    try {
      sslEngine.beginHandshake();
    } catch (SSLException sslException) {
      log.error("A problem was encountered while signaling the SSLEngine to begin a new handshake", sslException);
    } catch (IllegalStateException e){
      log.error("Client/server mode has not yet been set", e);
    }

    SSLEngineResult result;
    handshakeStatus = sslEngine.getHandshakeStatus();

    while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
                handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
      // Set a timeout exception for TLS handshake
      if (System.nanoTime() - startTimeDoTlsHandshake > TimeUnit.MILLISECONDS.toNanos(timeoutInMillis)) {
        throw new OperationTimeoutException("Timeout during TLS handshake");
      }
      
      switch (handshakeStatus) {
        case NEED_UNWRAP:
          if (socketChannel.read(peerNetData) < 0) {
            // The channel has reached end-of-stream
            if (sslEngine.isInboundDone() && sslEngine.isOutboundDone()) return false;
            handleEndOfStream();
            handshakeStatus = sslEngine.getHandshakeStatus();
            break;
          }

          // Process incoming handshaking data.
          peerNetData.flip();
          try {
            result = sslEngine.unwrap(peerNetData, peerAppData);
            // peerAppData will never be used anywhere in the handshake process, so it's safe to clear.
            peerAppData.clear();
            peerNetData.compact(); 
            handshakeStatus = result.getHandshakeStatus();
          } catch (SSLException sslException) {
            log.error("A problem that caused the SSLEngine to abort", sslException);
            sslEngine.closeOutbound();
            handshakeStatus = sslEngine.getHandshakeStatus();
            break;
          }

          // Handle handshake result after unwrap().
          boolean shouldContinue = handleUnwrapResultForHandshake(result, session);
          if (!shouldContinue){
            return false;
          }
          break;
        case NEED_WRAP:
          // Empty the network packet buffer.
          myNetData.clear();
          try {
            // Generate handshaking data.
            result = sslEngine.wrap(myAppData, myNetData);
            handshakeStatus = result.getHandshakeStatus();
          } catch (SSLException sslException) {
            log.error("Cannot process the data through SSLEngine. Will close the connection", sslException);
            sslEngine.closeOutbound();
            handshakeStatus = sslEngine.getHandshakeStatus();
            break;
          }

          // Handle handshake result after wrap().
          handleWrapResultForHandshake(result, session);
          break;
        case NEED_TASK:
          Runnable task;
          while ((task = sslEngine.getDelegatedTask()) != null) {
            task.run();
          }
          handshakeStatus = sslEngine.getHandshakeStatus();
          break;
        default:
          throw new IllegalArgumentException("Invalid handshake status: " + handshakeStatus);
      }
    }

    log.info("SSL handshake completed with status code " + handshakeStatus.toString());
    if (handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
      peerNetData.compact();
      return true;
    } 
    return false;
  }

  /** 
  * Convert plain text data record to encrypted data.
  * 
  * @return the number of bytes produced if successfully encrypted plain text data record, 
  *         -1 if the encryption fails with BUFFER_OVERFLOW error,
  *         0 if not encrypt any data record.
  */ 
  public int encryptNextTLSDataRecord(ByteBuffer obuf, ByteBuffer wbuf) throws IOException {
    while (obuf.hasRemaining() && wbuf.hasRemaining()) {
      SSLEngineResult result = sslEngine.wrap(obuf, wbuf);
      switch (result.getStatus()) {
        case OK:
          return result.bytesProduced();
        case BUFFER_OVERFLOW:
          return -1;
        case CLOSED:
          throw new RuntimeException("TLS connection is closed...");
        default:
          throw new IllegalArgumentException("Invalid result status after wrap(): " + result.getStatus());
      }
    }
    return 0;
  }

  /** 
  * Decrypt the next TLS data record to plain text data.
  * 
  * @param rbuf the buffer that contains TLS data record which will be decrypted.
  * @return buffer contains plain text data, null if unable to decrypt the data contained in the given buffer
  */ 
  public ByteBuffer decryptNextTLSDataRecord(ByteBuffer rbuf) throws IOException {
    SSLSession session = sslEngine.getSession();
    peerAppData.clear();

    while(true) {
      SSLEngineResult result = sslEngine.unwrap(rbuf, peerAppData);
      switch (result.getStatus()) {
        case OK:
          peerAppData.flip();
          return peerAppData;
        case BUFFER_OVERFLOW:
          // We need to enlarge the peer application data buffer.
          peerAppData = enlargeBuffer(peerAppData, session.getApplicationBufferSize());
          break;
        case BUFFER_UNDERFLOW:
          // We need to read more data into peer network buffer to form a complete packet for TLS unwrap.
          return null;
        case CLOSED:
          sslEngine.closeOutbound();
          socketChannel.close();
          throw new IOException("The operation could not be completed because it was already closed");
        default:
          throw new IllegalArgumentException("Invalid result status after unwrap(): " + result.getStatus());
      }
    }
  }

  /** 
  * Enlarge buffer capacity to handle BUFFER_OVERFLOW and BUFFER_UNDERFLOW result during wrap() and unwrap().
  * 
  * @return appropriate ByteBuffer.
  */ 
  private ByteBuffer enlargeBuffer(ByteBuffer buffer, int requiredBufferSize) {
    if (requiredBufferSize > buffer.capacity()) {
      ByteBuffer newBuffer = ByteBuffer.allocate(requiredBufferSize);
      buffer.flip();
      newBuffer.put(buffer);
      return newBuffer;
    }
    return buffer;
  }

  /** 
  * Handle end-of-stream.
  */ 
  private void handleEndOfStream() throws IOException {
    try {
      sslEngine.closeInbound();
    } catch (SSLException e) {
      log.error("This engine has not received the proper SSL/TLS close notification " +
                "message from the peer due to end of stream.", e);
    }
    sslEngine.closeOutbound();
  }

  /** 
  * Handle the SSL handshake result after unwrap().
  * 
  * @return true if the handshake process can continue, false if the handshake failed.
  */ 
  private boolean handleUnwrapResultForHandshake(SSLEngineResult result, SSLSession session) throws IOException {
    switch (result.getStatus()) {
      case OK: 
        // Successfully unwrapped the encrypted message
        break;
      case BUFFER_OVERFLOW: 
        // Often occur when peerAppData's capacity is not enough to consume data from peerNetData during unwrap().
        peerAppData = enlargeBuffer(peerAppData, session.getApplicationBufferSize());
        break;
      case BUFFER_UNDERFLOW: 
        break;
      case CLOSED:
        if (sslEngine.isOutboundDone()) {
          return false;
        } else {
          sslEngine.closeOutbound();
          handshakeStatus = sslEngine.getHandshakeStatus();
          break;
        }
      default:
        throw new IllegalArgumentException("Invalid result status after unwrap(): " + result.getStatus());
    }
    return true;
  }

  private void writeDataToSocketChannel(ByteBuffer buf) throws IOException {
    if (buf.hasRemaining()) {
      socketChannel.write(buf);
    }
  }

  /** 
  * Handle the SSL handshake result after wrap().
  */ 
  private void handleWrapResultForHandshake(SSLEngineResult result, SSLSession session) throws IOException {
    switch (result.getStatus()) {
      case OK : 
        // Successfully encrypted the application data
        myNetData.flip();
          
        // If there are remaining data in my network data buffer, send it to the peer.
        writeDataToSocketChannel(myNetData);
        break;
      case BUFFER_OVERFLOW:
        // Occur when myNetData's capacity is not enough to consume data from myAppData during wrap().
        myNetData = enlargeBuffer(myNetData, session.getPacketBufferSize());
        break;
      case BUFFER_UNDERFLOW:
        // This case is almost never happen since it only the start of handshake process. We don't have a minimum limit for myAppData.
        throw new SSLException("Buffer underflow occured after a wrap");
      case CLOSED:
        try {
          myNetData.flip();
          writeDataToSocketChannel(myNetData);
          // At this point the handshake status will probably be NEED_UNWRAP so we make sure that peerNetData is clear to read.
          handshakeStatus = sslEngine.getHandshakeStatus();
          peerNetData.clear();
        } catch (Exception e) {
          log.error("Failed to send server's CLOSE message due to socket channel's failure", e);
          handshakeStatus = sslEngine.getHandshakeStatus();
        }
        break;
      default:
        throw new IllegalArgumentException("Invalid result status after wrap(): " + result.getStatus());
    }
  }
}