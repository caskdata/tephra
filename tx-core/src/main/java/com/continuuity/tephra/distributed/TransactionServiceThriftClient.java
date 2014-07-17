/*
 * Copyright 2012-2014 Continuuity, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.continuuity.tephra.distributed;

import com.continuuity.tephra.Transaction;
import com.continuuity.tephra.distributed.thrift.TTransactionServer;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TTransport;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collection;

/**
 * This class is a wrapper around the thrift tx service client, it takes
 * Operations, converts them into thrift objects, calls the thrift
 * client, and converts the results back to data fabric classes.
 * This class also instruments the thrift calls with metrics.
 */
public class TransactionServiceThriftClient {
  private static final Function<byte[], ByteBuffer> BYTES_WRAPPER = new Function<byte[], ByteBuffer>() {
    @Override
    public ByteBuffer apply(byte[] input) {
      return ByteBuffer.wrap(input);
    }
  };

  /**
   * The thrift transport layer. We need this when we close the connection.
   */
  TTransport transport;

  /**
   * The actual thrift client.
   */
  TTransactionServer.Client client;

  /**
   * Constructor from an existing, connected thrift transport.
   *
   * @param transport the thrift transport layer. It must already be comnnected
   */
  public TransactionServiceThriftClient(TTransport transport) {
    this.transport = transport;
    // thrift protocol layer, we use binary because so does the service
    TProtocol protocol = new TBinaryProtocol(transport);
    // and create a thrift client
    this.client = new TTransactionServer.Client(protocol);
  }

  /**
   * close this client. may be called multiple times
   */
  public void close() {
    if (this.transport.isOpen()) {
      this.transport.close();
    }
  }

  public Transaction startLong() throws TException {
    return ConverterUtils.unwrap(client.startLong());
  }

  public Transaction startShort() throws TException {
      return ConverterUtils.unwrap(client.startShort());
  }

  public Transaction startShort(int timeout) throws TException {
      return ConverterUtils.unwrap(client.startShortTimeout(timeout));
  }

  public boolean canCommit(Transaction tx, Collection<byte[]> changeIds) throws TException {

      return client.canCommitTx(ConverterUtils.wrap(tx),
                                ImmutableSet.copyOf(Iterables.transform(changeIds, BYTES_WRAPPER))).isValue();
  }

  public boolean commit(Transaction tx) throws TException {

      return client.commitTx(ConverterUtils.wrap(tx)).isValue();
  }

  public void abort(Transaction tx) throws TException {
      client.abortTx(ConverterUtils.wrap(tx));
  }

  public boolean invalidate(long tx) throws TException {
    return client.invalidateTx(tx);
  }

  public InputStream getSnapshotStream() throws TException {
    ByteBuffer buffer = client.getSnapshot();
    if (buffer.hasArray()) {
      return new ByteArrayInputStream(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
    }

    // The ByteBuffer is not backed by array. Read the content to a new byte array and return an InputStream of that.
    byte[] snapshot = new byte[buffer.remaining()];
    buffer.get(snapshot);
    return new ByteArrayInputStream(snapshot);
  }

  public String status() throws TException { return client.status(); }

  public void resetState() throws TException {
    client.resetState();
  }
}
