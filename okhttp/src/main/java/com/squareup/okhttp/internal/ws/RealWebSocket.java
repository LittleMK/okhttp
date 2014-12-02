/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal.ws;

import com.squareup.okhttp.internal.NamedRunnable;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Random;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;

import static com.squareup.okhttp.internal.ws.WebSocketReader.FrameCallback;
import static java.util.concurrent.TimeUnit.SECONDS;

public class RealWebSocket implements WebSocket {
  /** A close code which indicates that the peer encountered a protocol exception. */
  private static final int CLOSE_PROTOCOL_EXCEPTION = 1002;

  private final WebSocketWriter writer;
  private final WebSocketReader reader;
  private final WebSocketListener listener;

  /** True after calling {@link #close(int, String)}. No writes are allowed afterward. */
  private volatile boolean writerClosed;
  /** True after a close frame was read by the reader. No frames will follow it. */
  private volatile boolean readerClosed;
  /** Lock required to close the connection. */
  private final Object closeLock = new Object();

  public RealWebSocket(boolean isClient, BufferedSource source, BufferedSink sink, Random random,
      WebSocketListener listener) {
    this.listener = listener;

    // Pings come in on the reader thread. This executor contends with callers for writing pongs.
    final ThreadPoolExecutor pongExecutor =
        new ThreadPoolExecutor(1, 1, 1, SECONDS, new LinkedBlockingDeque<Runnable>());
    pongExecutor.allowCoreThreadTimeOut(true);

    writer = new WebSocketWriter(isClient, sink, random);
    reader = new WebSocketReader(isClient, source, listener, new FrameCallback() {
      @Override public void onPing(final Buffer buffer) {
        pongExecutor.execute(new NamedRunnable("WebSocket PongWriter") {
          @Override protected void execute() {
            try {
              writer.writePong(buffer);
            } catch (IOException ignored) {
            }
          }
        });
      }

      @Override public void onClose(Buffer buffer) throws IOException {
        peerClose(buffer);
      }
    });
  }

  void runReader() {
    while (!readerClosed) {
      try {
        reader.readMessage();
      } catch (IOException e) {
        readerErrorClose(e, listener);
        return;
      }
    }

  }

  @Override public BufferedSink newMessageSink(PayloadType type) {
    if (writerClosed) throw new IllegalStateException("Closed");
    return writer.newMessageSink(type);
  }

  @Override public void sendMessage(PayloadType type, Buffer payload) throws IOException {
    if (writerClosed) throw new IllegalStateException("Closed");
    writer.sendMessage(type, payload);
  }

  @Override public void close(int code, String reason) throws IOException {
    boolean closeConnection;
    synchronized (closeLock) {
      if (writerClosed) return;
      writerClosed = true;

      // If the reader has also indicated a desire to close we will close the connection.
      closeConnection = readerClosed;
    }

    writer.writeClose(code, reason);

    if (closeConnection) {
      closeConnection();
    }
  }

  /** Called on the reader thread when a close frame is encountered. */
  private void peerClose(Buffer buffer) throws IOException {
    boolean closeConnection;
    synchronized (closeLock) {
      readerClosed = true;

      // If the writer has already indicated a desire to close we will close the connection.
      closeConnection = writerClosed;
      writerClosed = true;
    }

    if (closeConnection) {
      closeConnection();
    } else {
      // The reader thread will read no more frames so use it to send the response.
      writer.writeClose(buffer);
    }
  }

  /** Called on the reader thread when an error occurs. */
  private void readerErrorClose(IOException e, WebSocketListener listener) {
    boolean closeConnection;
    synchronized (closeLock) {
      readerClosed = true;

      // If the writer has not closed we will close the connection.
      closeConnection = !writerClosed;
      writerClosed = true;
    }

    if (closeConnection) {
      if (e instanceof ProtocolException) {
        // For protocol exceptions, try to inform the server of such.
        try {
          writer.writeClose(CLOSE_PROTOCOL_EXCEPTION, null);
        } catch (IOException ignored) {
        }
      }

      try {
        closeConnection();
      } catch (IOException ignored) {
      }
    }

    listener.onFailure(e);
  }

  /** Perform any tear-down work on the connection (close the socket, recycle, etc.). */
  protected void closeConnection() throws IOException {
  }

  @Override public boolean isClosed() {
    return writerClosed;
  }
}
