/*
 * Copyright © 2012-2014 Cask Data, Inc.
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

package co.cask.tephra.distributed;

import co.cask.tephra.InvalidTruncateTimeException;
import co.cask.tephra.Transaction;
import co.cask.tephra.TransactionCouldNotTakeSnapshotException;
import co.cask.tephra.TransactionNotInProgressException;
import co.cask.tephra.TransactionSystemClient;
import co.cask.tephra.TxConstants;
import co.cask.tephra.distributed.thrift.TInvalidTruncateTimeException;
import co.cask.tephra.distributed.thrift.TTransactionCouldNotTakeSnapshotException;
import co.cask.tephra.distributed.thrift.TTransactionNotInProgressException;
import co.cask.tephra.runtime.ConfigModule;
import co.cask.tephra.runtime.DiscoveryModules;
import co.cask.tephra.runtime.TransactionClientModule;
import co.cask.tephra.runtime.TransactionModules;
import co.cask.tephra.runtime.ZKModule;
import co.cask.tephra.util.ConfigurationFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.hadoop.conf.Configuration;
import org.apache.thrift.TException;
import org.apache.twill.zookeeper.ZKClientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * A tx service client
 */
public class TransactionServiceClient implements TransactionSystemClient {

  private static final Logger LOG =
      LoggerFactory.getLogger(TransactionServiceClient.class);

  // we will use this to provide every call with an tx client
  private ThriftClientProvider clientProvider;

  // the retry strategy we will use
  private final RetryStrategyProvider retryStrategyProvider;

  /**
   * Utility to be used for basic verification of transaction system availability and functioning
   * @param args arguments list, accepts single option "-v" that makes it to print out more details about started tx
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {
    if (args.length > 1 || (args.length == 1 && !"-v".equals(args[0]))) {
      System.out.println("USAGE: TransactionServiceClient [-v]");
    }

    boolean verbose = false;
    if (args.length == 1 && "-v".equals(args[0])) {
      verbose = true;
    }
    doMain(verbose, new ConfigurationFactory().get());
  }

  @VisibleForTesting
  public static void doMain(boolean verbose, Configuration conf) throws Exception {
    LOG.info("Starting tx server client test.");
    Injector injector = Guice.createInjector(
      new ConfigModule(conf),
      new ZKModule(),
      new DiscoveryModules().getDistributedModules(),
      new TransactionModules().getDistributedModules(),
      new TransactionClientModule()
    );

    ZKClientService zkClient = injector.getInstance(ZKClientService.class);
    zkClient.startAndWait();

    try {
      TransactionServiceClient client = injector.getInstance(TransactionServiceClient.class);
      LOG.info("Starting tx...");
      Transaction tx = client.startShort();
      if (verbose) {
        LOG.info("Started tx details: " + tx.toString());
      } else {
        LOG.info("Started tx: " + tx.getTransactionId() +
                   ", readPointer: " + tx.getReadPointer() +
                   ", invalids: " + tx.getInvalids().length +
                   ", inProgress: " + tx.getInProgress().length);
      }
      LOG.info("Checking if canCommit tx...");
      boolean canCommit = client.canCommit(tx, Collections.<byte[]>emptyList());
      LOG.info("canCommit: " + canCommit);
      if (canCommit) {
        LOG.info("Committing tx...");
        boolean committed = client.commit(tx);
        LOG.info("Committed tx: " + committed);
        if (!committed) {
          LOG.info("Aborting tx...");
          client.abort(tx);
          LOG.info("Aborted tx...");
        }
      } else {
        LOG.info("Aborting tx...");
        client.abort(tx);
        LOG.info("Aborted tx...");
      }
    } finally {
      zkClient.stopAndWait();
    }
  }

  /**
   * Create from a configuration. This will first attempt to find a zookeeper
   * for service discovery. Otherwise it will look for the port in the
   * config and use localhost.
   * @param config a configuration containing the zookeeper properties
   */
  @Inject
  public TransactionServiceClient(Configuration config,
                                  ThriftClientProvider clientProvider) {

    // initialize the retry logic
    String retryStrat = config.get(
        TxConstants.Service.CFG_DATA_TX_CLIENT_RETRY_STRATEGY,
        TxConstants.Service.DEFAULT_DATA_TX_CLIENT_RETRY_STRATEGY);
    if ("backoff".equals(retryStrat)) {
      this.retryStrategyProvider = new RetryWithBackoff.Provider();
    } else if ("n-times".equals(retryStrat)) {
      this.retryStrategyProvider = new RetryNTimes.Provider();
    } else {
      String message = "Unknown Retry Strategy '" + retryStrat + "'.";
      LOG.error(message);
      throw new IllegalArgumentException(message);
    }
    this.retryStrategyProvider.configure(config);
    LOG.debug("Retry strategy is " + this.retryStrategyProvider);

    this.clientProvider = clientProvider;
  }

  /**
   * This is an abstract class that encapsulates an operation. It provides a
   * method to attempt the actual operation, and it can throw an operation
   * exception.
   * @param <T> The return type of the operation
   */
  abstract static class Operation<T> {

    /** the name of the operation. */
    String name;

    /** constructor with name of operation. */
    Operation(String name) {
      this.name = name;
    }

    /** return the name of the operation. */
    String getName() {
      return name;
    }

    /** execute the operation, given an tx client. */
    abstract T execute(TransactionServiceThriftClient client)
        throws Exception;
  }

  /** see execute(operation, client). */
  private <T> T execute(Operation<T> operation) throws Exception {
    return execute(operation, null);
  }

  /**
   * This is a generic method implementing the somewhat complex execution
   * and retry logic for operations, to avoid repetitive code.
   *
   * Attempts to execute one operation, by obtaining an tx client from
   * the client provider and passing the operation to the client. If the
   * call fails with a Thrift exception, apply the retry strategy. If no
   * more retries are to be made according to the strategy, call the
   * operation's error method to obtain a value to return. Note that error()
   * may also throw an exception. Note also that the retry logic is only
   * applied for thrift exceptions.
   *
   * @param operation The operation to be executed
   * @param provider An tx client provider. If null, then a client will be
   *                 obtained using the client provider
   * @param <T> The return type of the operation
   * @return the result of the operation, or a value returned by error()
   */
  private <T> T execute(Operation<T> operation, ThriftClientProvider provider) throws Exception {
    RetryStrategy retryStrategy = retryStrategyProvider.newRetryStrategy();
    while (true) {
      // did we get a custom client provider or do we use the default?
      if (provider == null) {
        provider = this.clientProvider;
      }
      TransactionServiceThriftClient client = null;
      try {
        // this will throw a TException if it cannot get a client
        client = provider.getClient();

        // note that this can throw exceptions other than TException
        // hence the finally clause at the end
        return operation.execute(client);

      } catch (TException te) {
        // a thrift error occurred, the thrift client may be in a bad state
        if (client != null) {
          provider.discardClient(client);
          client = null;
        }

        // determine whether we should retry
        boolean retry = retryStrategy.failOnce();
        if (!retry) {
          // retry strategy is exceeded, throw an operation exception
          String message =
              "Thrift error for " + operation + ": " + te.getMessage();
          LOG.error(message);
          LOG.debug(message, te);
          throw new Exception(message, te);
        } else {
          // call retry strategy before retrying
          retryStrategy.beforeRetry();
          String msg = "Retrying " + operation.getName() + " after Thrift error: " + te.getMessage();
          LOG.info(msg);
          LOG.debug(msg, te);
        }

      } finally {
        // in case any other exception happens (other than TException), and
        // also in case of success, the client must be returned to the pool.
        if (client != null) {
          provider.returnClient(client);
        }
      }
    }
  }

  @Override
  public Transaction startLong() {
    try {
      return execute(
        new Operation<Transaction>("startLong") {
          @Override
          public Transaction execute(TransactionServiceThriftClient client)
            throws TException {
            return client.startLong();
          }
        });
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public Transaction startShort() {
    try {
      return execute(
        new Operation<Transaction>("startShort") {
          @Override
          public Transaction execute(TransactionServiceThriftClient client)
            throws TException {
            return client.startShort();
          }
        });
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public Transaction startShort(final int timeout) {
    try {
      return execute(
        new Operation<Transaction>("startShort") {
          @Override
          public Transaction execute(TransactionServiceThriftClient client)
            throws TException {
            return client.startShort(timeout);
          }
        });
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public boolean canCommit(final Transaction tx, final Collection<byte[]> changeIds)
    throws TransactionNotInProgressException {

    try {
      return execute(
        new Operation<Boolean>("canCommit") {
          @Override
          public Boolean execute(TransactionServiceThriftClient client)
            throws Exception {
            try {
              return client.canCommit(tx, changeIds);
            } catch (TTransactionNotInProgressException e) {
              throw new TransactionNotInProgressException(e.getMessage());
            }
          }
        });
    } catch (TransactionNotInProgressException e) {
      throw e;
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public boolean commit(final Transaction tx) throws TransactionNotInProgressException {
    try {
      return this.execute(
        new Operation<Boolean>("commit") {
          @Override
          public Boolean execute(TransactionServiceThriftClient client)
            throws Exception {
            try {
              return client.commit(tx);
            } catch (TTransactionNotInProgressException e) {
              throw new TransactionNotInProgressException(e.getMessage());
            }
          }
        });
    } catch (TransactionNotInProgressException e) {
      throw e;
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void abort(final Transaction tx) {
    try {
      this.execute(
        new Operation<Boolean>("abort") {
          @Override
          public Boolean execute(TransactionServiceThriftClient client)
            throws TException {
            client.abort(tx);
            return true;
          }
        });
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public boolean invalidate(final long tx) {
    try {
      return this.execute(
        new Operation<Boolean>("invalidate") {
          @Override
          public Boolean execute(TransactionServiceThriftClient client)
            throws TException {
            return client.invalidate(tx);
          }
        });
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public Transaction checkpoint(final Transaction tx) throws TransactionNotInProgressException {
    try {
      return this.execute(
          new Operation<Transaction>("checkpoint") {
            @Override
            Transaction execute(TransactionServiceThriftClient client) throws Exception {
              return client.checkpoint(tx);
            }
          }
      );
    } catch (TransactionNotInProgressException te) {
      throw te;
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public InputStream getSnapshotInputStream() throws TransactionCouldNotTakeSnapshotException {
    try {
      return this.execute(
          new Operation<InputStream>("takeSnapshot") {
            @Override
            public InputStream execute(TransactionServiceThriftClient client)
                throws Exception {
              try {
                return client.getSnapshotStream();
              } catch (TTransactionCouldNotTakeSnapshotException e) {
                throw new TransactionCouldNotTakeSnapshotException(e.getMessage());
              }
            }
          });
    } catch (TransactionCouldNotTakeSnapshotException e) {
      throw e;
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public String status() {
    try {
      return this.execute(
        new Operation<String>("status") {
          @Override
          public String execute(TransactionServiceThriftClient client) throws Exception {
            return client.status();
          }
        });
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public void resetState() {
    try {
      this.execute(
          new Operation<Boolean>("resetState") {
            @Override
            public Boolean execute(TransactionServiceThriftClient client)
                throws TException {
              client.resetState();
              return true;
            }
          });
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public boolean truncateInvalidTx(final Set<Long> invalidTxIds) {
    try {
      return this.execute(
        new Operation<Boolean>("truncateInvalidTx") {
          @Override
          public Boolean execute(TransactionServiceThriftClient client) throws TException {
            return client.truncateInvalidTx(invalidTxIds);
          }
        });
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public boolean truncateInvalidTxBefore(final long time) throws InvalidTruncateTimeException {
    try {
      return this.execute(
        new Operation<Boolean>("truncateInvalidTxBefore") {
          @Override
          public Boolean execute(TransactionServiceThriftClient client) throws Exception {
            try {
              return client.truncateInvalidTxBefore(time);
            } catch (TInvalidTruncateTimeException e) {
              throw new InvalidTruncateTimeException(e.getMessage());
            }
          }
        });
    } catch (InvalidTruncateTimeException e) {
      throw e;
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @Override
  public int getInvalidSize() {
    try {
      return this.execute(
        new Operation<Integer>("getInvalidSize") {
          @Override
          public Integer execute(TransactionServiceThriftClient client) throws TException {
            return client.getInvalidSize();
          }
        });
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }
}
