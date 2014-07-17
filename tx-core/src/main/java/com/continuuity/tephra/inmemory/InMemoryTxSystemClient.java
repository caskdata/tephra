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

package com.continuuity.tephra.inmemory;

import com.continuuity.tephra.Transaction;
import com.continuuity.tephra.TransactionCouldNotTakeSnapshotException;
import com.continuuity.tephra.TransactionNotInProgressException;
import com.continuuity.tephra.TransactionSystemClient;
import com.continuuity.tephra.TxConstants;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

/**
 *
 */
public class InMemoryTxSystemClient implements TransactionSystemClient {

  private static final Logger LOG = LoggerFactory.getLogger(InMemoryTxSystemClient.class);

  InMemoryTransactionManager txManager;

  @Inject
  public InMemoryTxSystemClient(InMemoryTransactionManager txmgr) {
    txManager = txmgr;
  }

  @Override
  public Transaction startLong() {
    return txManager.startLong();
  }

  @Override
  public Transaction startShort() {
    return txManager.startShort();
  }

  @Override
  public Transaction startShort(int timeout) {
    return txManager.startShort(timeout);
  }

  @Override
  public boolean canCommit(Transaction tx, Collection<byte[]> changeIds) throws TransactionNotInProgressException {
    return changeIds.isEmpty() || txManager.canCommit(tx, changeIds);
  }

  @Override
  public boolean commit(Transaction tx) throws TransactionNotInProgressException {
    return txManager.commit(tx);
  }

  @Override
  public void abort(Transaction tx) {
    txManager.abort(tx);
  }

  @Override
  public boolean invalidate(long tx) {
    return txManager.invalidate(tx);
  }

  @Override
  public InputStream getSnapshotInputStream() throws TransactionCouldNotTakeSnapshotException {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try {
        boolean snapshotTaken = txManager.takeSnapshot(out);
        if (!snapshotTaken) {
          throw new TransactionCouldNotTakeSnapshotException("Transaction manager did not take a snapshot.");
        }
      } finally {
        out.close();
      }
      return new ByteArrayInputStream(out.toByteArray());
    } catch (IOException e) {
      LOG.error("Snapshot could not be taken", e);
      throw new TransactionCouldNotTakeSnapshotException(e.getMessage());
    }
  }

  @Override
  public String status() {
    return txManager.isRunning() ? TxConstants.STATUS_OK : TxConstants.STATUS_NOTOK;
  }

  @Override
  public void resetState() {
    txManager.resetState();
  }
}
