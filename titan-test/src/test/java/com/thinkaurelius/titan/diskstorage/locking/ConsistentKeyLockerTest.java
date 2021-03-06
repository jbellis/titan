package com.thinkaurelius.titan.diskstorage.locking;

import static com.thinkaurelius.titan.diskstorage.locking.consistentkey.ConsistentKeyLocker.LOCK_COL_END;
import static com.thinkaurelius.titan.diskstorage.locking.consistentkey.ConsistentKeyLocker.LOCK_COL_START;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.thinkaurelius.titan.core.time.SimpleDuration;
import com.thinkaurelius.titan.core.time.Timepoint;
import com.thinkaurelius.titan.core.time.TimestampProvider;
import com.thinkaurelius.titan.diskstorage.util.*;
import com.thinkaurelius.titan.diskstorage.util.KeyColumn;

import org.easymock.EasyMock;
import org.easymock.IMocksControl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.thinkaurelius.titan.diskstorage.*;
import com.thinkaurelius.titan.diskstorage.configuration.Configuration;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyColumnValueStore;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeySliceQuery;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreManager;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreTransaction;
import com.thinkaurelius.titan.diskstorage.locking.consistentkey.ConsistentKeyLockStatus;
import com.thinkaurelius.titan.diskstorage.locking.consistentkey.ConsistentKeyLocker;
import com.thinkaurelius.titan.diskstorage.locking.consistentkey.ConsistentKeyLockerSerializer;
import com.thinkaurelius.titan.diskstorage.locking.consistentkey.LockCleanerService;
import com.thinkaurelius.titan.diskstorage.util.BufferUtil;

import org.easymock.LogicalOperator;

import java.util.Comparator;

import static org.easymock.EasyMock.*;


public class ConsistentKeyLockerTest {

    // Arbitrary literals -- the exact values assigned here are not intrinsically important
    private final ConsistentKeyLockerSerializer codec = new ConsistentKeyLockerSerializer();
    private final StaticBuffer defaultDataKey = BufferUtil.getIntBuffer(2);
    private final StaticBuffer defaultDataCol = BufferUtil.getIntBuffer(4);
    private final StaticBuffer defaultLockKey = codec.toLockKey(defaultDataKey, defaultDataCol);
    private final KeyColumn defaultLockID = new KeyColumn(defaultDataKey, defaultDataCol);

    private final StaticBuffer otherDataKey = BufferUtil.getIntBuffer(8);
    private final StaticBuffer otherDataCol = BufferUtil.getIntBuffer(16);
    private final StaticBuffer otherLockKey = codec.toLockKey(otherDataKey, otherDataCol);
    private final KeyColumn otherLockID = new KeyColumn(otherDataKey, otherDataCol);

    private final StaticBuffer defaultLockRid = new StaticArrayBuffer(new byte[]{(byte) 32});
    private final StaticBuffer otherLockRid = new StaticArrayBuffer(new byte[]{(byte) 64});
    private final StaticBuffer defaultLockVal = BufferUtil.getIntBuffer(0); // maybe refactor...

    private StoreTransaction defaultTx;
    private TransactionHandleConfig defaultTxCfg;
    private Configuration defaultTxCustomOpts;

    private StoreTransaction otherTx;
    private TransactionHandleConfig otherTxCfg;
    private Configuration otherTxCustomOpts;

    private final long defaultWaitNS = 100 * 1000 * 1000;
    private final long defaultExpireNS = 30L * 1000 * 1000 * 1000;

    private final int maxTemporaryStorageExceptions = 3;

    private IMocksControl ctrl;
    private IMocksControl relaxedCtrl;
    private long currentTimeNS;
    private TimestampProvider times;
    private KeyColumnValueStore store;
    private StoreManager manager;
    private LocalLockMediator<StoreTransaction> mediator;
    private LockerState<ConsistentKeyLockStatus> lockState;
    private ConsistentKeyLocker locker;

    @SuppressWarnings("unchecked")
    @Before
    public void setupMocks() throws StorageException {
        currentTimeNS = 0;

        /*
         * relaxedControl doesn't care about the order in which its mocks'
         * methods are called. This is useful for mocks of immutable objects.
         */
        relaxedCtrl = EasyMock.createControl();

        manager = relaxedCtrl.createMock(StoreManager.class);

        defaultTx = relaxedCtrl.createMock(StoreTransaction.class);
        defaultTxCfg = relaxedCtrl.createMock(TransactionHandleConfig.class);
        defaultTxCustomOpts = relaxedCtrl.createMock(Configuration.class);
        expect(defaultTx.getConfiguration()).andReturn(defaultTxCfg).anyTimes();
        expect(defaultTxCfg.getGroupName()).andReturn("default").anyTimes();
        expect(defaultTxCfg.getCustomOptions()).andReturn(defaultTxCustomOpts).anyTimes();
        Comparator<TransactionHandleConfig> defaultTxCfgChecker = new Comparator<TransactionHandleConfig>() {
            @Override
            public int compare(TransactionHandleConfig actual, TransactionHandleConfig ignored) {
                return actual.getCustomOptions() == defaultTxCustomOpts ? 0 : -1;
            }
        };
        expect(manager.beginTransaction(cmp(null, defaultTxCfgChecker, LogicalOperator.EQUAL))).andReturn(defaultTx).anyTimes();

        otherTx = relaxedCtrl.createMock(StoreTransaction.class);
        otherTxCfg = relaxedCtrl.createMock(TransactionHandleConfig.class);
        otherTxCustomOpts = relaxedCtrl.createMock(Configuration.class);
        expect(otherTx.getConfiguration()).andReturn(otherTxCfg).anyTimes();
        expect(otherTxCfg.getGroupName()).andReturn("other").anyTimes();
        expect(otherTxCfg.getCustomOptions()).andReturn(otherTxCustomOpts).anyTimes();
        Comparator<TransactionHandleConfig> otherTxCfgChecker = new Comparator<TransactionHandleConfig>() {
            @Override
            public int compare(TransactionHandleConfig actual, TransactionHandleConfig ignored) {
                return actual.getCustomOptions() == otherTxCustomOpts ? 0 : -1;
            }
        };
        expect(manager.beginTransaction(cmp(null, otherTxCfgChecker, LogicalOperator.EQUAL))).andReturn(otherTx).anyTimes();


        /*
         * ctrl requires that the complete, order-sensitive sequence of actual
         * method invocations on its mocks exactly match the expected sequence
         * hard-coded into each test method. Either an unexpected actual
         * invocation or expected invocation that fails to actually occur will
         * cause a test failure.
         */
        ctrl = EasyMock.createStrictControl();
        times = ctrl.createMock(TimestampProvider.class);
        store = ctrl.createMock(KeyColumnValueStore.class);
        mediator = ctrl.createMock(LocalLockMediator.class);
        lockState = ctrl.createMock(LockerState.class);
        expect(times.getUnit()).andReturn(TimeUnit.NANOSECONDS).atLeastOnce();
        ctrl.replay();
        locker = getDefaultBuilder().build();
        ctrl.verify();
        ctrl.reset();

//        locker = new ConsistentKeyLocker.Builder(store, manager)
//                .times(times)
//                .mediator(mediator)
//                .internalState(lockState)
//                .lockExpireNS(defaultExpireNS, TimeUnit.NANOSECONDS)
//                .lockWaitNS(defaultWaitNS, TimeUnit.NANOSECONDS)
//                .rid(defaultLockRid).build();

        relaxedCtrl.replay();
    }

    @After
    public void verifyMocks() {
        ctrl.verify();
        relaxedCtrl.verify();
    }

    /**
     * Test a single lock using stub objects. Doesn't test unlock ("leaks" the
     * lock, but since it's backed by stubs, it doesn't matter).
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testWriteLockInSimplestCase() throws StorageException {

        // Check to see whether the lock was already written before anything else
        expect(lockState.has(defaultTx, defaultLockID)).andReturn(false);
        // Now lock it locally to block other threads in the process
        recordSuccessfulLocalLock();
        // Write a lock claim column to the store
        LockInfo li = recordSuccessfulLockWrite(1, TimeUnit.NANOSECONDS, null);
        // Update the expiration timestamp of the local (thread-level) lock
        recordSuccessfulLocalLock(li.tsNS);
        // Store the taken lock's key, column, and timestamp in the lockState map
        lockState.take(eq(defaultTx), eq(defaultLockID), eq(li.stat));
        ctrl.replay();

        locker.writeLock(defaultLockID, defaultTx); // SUT
    }

    /**
     * Test locker when first attempt to write to the store takes too long (but
     * succeeds). Expected behavior is to call mutate on the store, adding a
     * column with a new timestamp and deleting the column with the old
     * (too-slow-to-write) timestamp.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testWriteLockRetriesAfterOneStoreTimeout() throws StorageException {
        expect(lockState.has(defaultTx, defaultLockID)).andReturn(false);
        recordSuccessfulLocalLock();
        StaticBuffer firstCol = recordSuccessfulLockWrite(5, TimeUnit.SECONDS, null).col; // too slow
        LockInfo secondLI = recordSuccessfulLockWrite(1, TimeUnit.NANOSECONDS, firstCol); // plenty fast
        recordSuccessfulLocalLock(secondLI.tsNS);
        lockState.take(eq(defaultTx), eq(defaultLockID), eq(secondLI.stat));
        ctrl.replay();

        locker.writeLock(defaultLockID, defaultTx); // SUT
    }

    /**
     * Test locker when all three attempts to write a lock succeed but take
     * longer than the wait limit. We expect the locker to delete all three
     * columns that it wrote and locally unlock the KeyColumn, then emit an
     * exception.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testWriteLockThrowsExceptionAfterMaxStoreTimeouts() throws StorageException {
        expect(lockState.has(defaultTx, defaultLockID)).andReturn(false);
        recordSuccessfulLocalLock();
        StaticBuffer firstCol = recordSuccessfulLockWrite(5, TimeUnit.SECONDS, null).col;
        StaticBuffer secondCol = recordSuccessfulLockWrite(5, TimeUnit.SECONDS, firstCol).col;
        StaticBuffer thirdCol = recordSuccessfulLockWrite(5, TimeUnit.SECONDS, secondCol).col;
        recordSuccessfulLockDelete(1, TimeUnit.NANOSECONDS, thirdCol);
        recordSuccessfulLocalUnlock();
        ctrl.replay();

        StorageException expected = null;
        try {
            locker.writeLock(defaultLockID, defaultTx); // SUT
        } catch (TemporaryStorageException e) {
            expected = e;
        }
        assertNotNull(expected);
    }

    /**
     * Test that the first {@link PermanentStorageException} thrown by the
     * locker's store causes it to attempt to delete outstanding lock writes and
     * then emit the exception without retrying.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testWriteLockDiesOnPermanentStorageException() throws StorageException {
        PermanentStorageException errOnFire = new PermanentStorageException("Storage cluster is on fire");

        expect(lockState.has(defaultTx, defaultLockID)).andReturn(false);
        recordSuccessfulLocalLock();
        StaticBuffer lockCol = recordExceptionLockWrite(1, TimeUnit.NANOSECONDS, null, errOnFire);
        recordSuccessfulLockDelete(1, TimeUnit.NANOSECONDS, lockCol);
        recordSuccessfulLocalUnlock();
        ctrl.replay();

        StorageException expected = null;
        try {
            locker.writeLock(defaultLockID, defaultTx); // SUT
        } catch (PermanentLockingException e) {
            expected = e;
        }
        assertNotNull(expected);
        assertEquals(errOnFire, expected.getCause());
    }

    /**
     * Test the locker retries a lock write after the initial store mutation
     * fails with a {@link TemporaryStorageException}. The retry should both
     * attempt to write the and delete the failed mutation column.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testWriteLockRetriesOnTemporaryStorageException() throws StorageException {
        TemporaryStorageException tse = new TemporaryStorageException("Storage cluster is waking up");

        expect(lockState.has(defaultTx, defaultLockID)).andReturn(false);
        recordSuccessfulLocalLock();
        StaticBuffer firstCol = recordExceptionLockWrite(1, TimeUnit.NANOSECONDS, null, tse);
        LockInfo secondLI = recordSuccessfulLockWrite(1, TimeUnit.NANOSECONDS, firstCol);
        recordSuccessfulLocalLock(secondLI.tsNS);
        lockState.take(eq(defaultTx), eq(defaultLockID), eq(secondLI.stat));
        ctrl.replay();

        locker.writeLock(defaultLockID, defaultTx); // SUT
    }

    /**
     * Test that a failure to lock locally results in a {@link TemporaryLockingException}
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testWriteLockFailsOnLocalContention() throws StorageException {

        expect(lockState.has(defaultTx, defaultLockID)).andReturn(false);
        recordFailedLocalLock();
        ctrl.replay();

        PermanentLockingException le = null;
        try {
            locker.writeLock(defaultLockID, defaultTx); // SUT
        } catch (PermanentLockingException e) {
            le = e;
        }
        assertNotNull(le);
    }

    /**
     * Claim a lock without errors using {@code defaultTx}, the check that
     * {@code otherTx} can't claim it, instead throwing a
     * TemporaryLockingException
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testWriteLockDetectsMultiTxContention() throws StorageException {
        // defaultTx

        // Check to see whether the lock was already written before anything else
        expect(lockState.has(defaultTx, defaultLockID)).andReturn(false);
        // Now lock it locally to block other threads in the process
        recordSuccessfulLocalLock();
        // Write a lock claim column to the store
        LockInfo li = recordSuccessfulLockWrite(1, TimeUnit.NANOSECONDS, null);
        // Update the expiration timestamp of the local (thread-level) lock
        recordSuccessfulLocalLock(li.tsNS);
        // Store the taken lock's key, column, and timestamp in the lockState map
        lockState.take(eq(defaultTx), eq(defaultLockID), eq(li.stat));

        // otherTx
        // Check to see whether the lock was already written before anything else
        expect(lockState.has(otherTx, defaultLockID)).andReturn(false);
        // Now try to take the lock but fail because defaultTX has it
        recordFailedLocalLock(otherTx);
        ctrl.replay();

        locker.writeLock(defaultLockID, defaultTx); // SUT

        PermanentLockingException le = null;
        try {
            locker.writeLock(defaultLockID, otherTx); // SUT
        } catch (PermanentLockingException e) {
            le = e;
        }
        assertNotNull(le);
    }

    /**
     * Test that multiple calls to
     * {@link ConsistentKeyLocker#writeLock(KeyColumn, StoreTransaction)} with
     * the same arguments have no effect after the first call (until
     * {@link ConsistentKeyLocker#deleteLocks(StoreTransaction)} is called).
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testWriteLockIdempotence() throws StorageException {
        expect(lockState.has(defaultTx, defaultLockID)).andReturn(false);
        recordSuccessfulLocalLock();
        LockInfo li = recordSuccessfulLockWrite(1, TimeUnit.NANOSECONDS, null);
        recordSuccessfulLocalLock(li.tsNS);
        lockState.take(eq(defaultTx), eq(defaultLockID), eq(li.stat));
        ctrl.replay();

        locker.writeLock(defaultLockID, defaultTx);

        ctrl.verify();
        ctrl.reset();
        expect(lockState.has(defaultTx, defaultLockID)).andReturn(true);
        ctrl.replay();

        locker.writeLock(defaultLockID, defaultTx);
    }

    /**
     * Test a single checking a single lock under optimal conditions (no
     * timeouts, no errors)
     *
     * @throws StorageException     shouldn't happen
     * @throws InterruptedException shouldn't happen
     */
    @Test
    public void testCheckLocksInSimplestCase() throws StorageException, InterruptedException {
        // Fake a pre-existing lock
        final ConsistentKeyLockStatus ls = makeStatusNow();
        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, ls));
        currentTimeNS += TimeUnit.NANOSECONDS.convert(10, TimeUnit.SECONDS);
        // Checker should compare the fake lock's timestamp to the current time
        expectSleepAfterWritingLock(ls);
        // Expect a store getSlice() and return the fake lock's column and value
        recordLockGetSliceAndReturnSingleEntry(
                StaticArrayEntry.of(
                        codec.toLockCol(ls.getWriteTimestamp(TimeUnit.NANOSECONDS), defaultLockRid),
                        defaultLockVal));
        ctrl.replay();

        locker.checkLocks(defaultTx);
    }

    private void expectSleepAfterWritingLock(ConsistentKeyLockStatus ls) throws InterruptedException {
        expect(times.sleepPast(new Timepoint(ls.getWriteTimestamp(TimeUnit.NANOSECONDS) + defaultWaitNS, TimeUnit.NANOSECONDS))).andReturn(new Timepoint(currentTimeNS, TimeUnit.NANOSECONDS));
    }

    /**
     * Lock checking should treat columns with timestamps older than the
     * expiration period as though they were never read from the store (aside
     * from logging them). This tests the checker with a single expired column.
     *
     * @throws StorageException     shouldn't happen
     * @throws InterruptedException
     */
    @Test
    public void testCheckLocksIgnoresSingleExpiredLock() throws StorageException, InterruptedException {
        // Fake a pre-existing lock that's long since expired
        final ConsistentKeyLockStatus expired = makeStatusNow();
        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, expired));
        currentTimeNS += TimeUnit.NANOSECONDS.convert(100, TimeUnit.DAYS); // pretend a huge multiple of the expiration time has passed

        // Checker should compare the fake lock's timestamp to the current time
        expectSleepAfterWritingLock(expired);

        // Checker must slice the store; we return the single expired lock column
        recordLockGetSliceAndReturnSingleEntry(
                StaticArrayEntry.of(
                        codec.toLockCol(expired.getWriteTimestamp(TimeUnit.NANOSECONDS), defaultLockRid),
                        defaultLockVal));

        ctrl.replay();
        TemporaryLockingException ple = null;
        try {
            locker.checkLocks(defaultTx);
        } catch (TemporaryLockingException e) {
            ple = e;
        }
        assertNotNull(ple);
    }

    /**
     * Each written lock should be checked at most once. Test this by faking a
     * single previously written lock using mocks and stubs and then calling
     * checkLocks() twice. The second call should have no effect.
     *
     * @throws InterruptedException shouldn't happen
     * @throws StorageException     shouldn't happen
     */
    @Test
    public void testCheckLocksIdempotence() throws InterruptedException, StorageException {
        // Fake a pre-existing valid lock
        final ConsistentKeyLockStatus ls = makeStatusNow();
        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, ls));
        currentTimeNS += TimeUnit.NANOSECONDS.convert(10, TimeUnit.SECONDS);

        expectSleepAfterWritingLock(ls);

        final StaticBuffer lc = codec.toLockCol(ls.getWriteTimestamp(TimeUnit.NANOSECONDS), defaultLockRid);
        recordLockGetSliceAndReturnSingleEntry(StaticArrayEntry.of(lc, defaultLockVal));
        ctrl.replay();

        locker.checkLocks(defaultTx);

        ctrl.verify();
        ctrl.reset();
        // Return the faked lock in a map of size 1
        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, ls));
        ctrl.replay();
        // At this point, checkLocks() should see that the single lock in the
        // map returned above has already been checked and return immediately

        locker.checkLocks(defaultTx);
    }

    /**
     * If the checker reads its own lock column preceeded by a lock column from
     * another rid with an earlier timestamp and the timestamps on both columns
     * are unexpired, then the checker must throw a TemporaryLockingException.
     *
     * @throws InterruptedException shouldn't happen
     * @throws StorageException     shouldn't happen (we expect a TemporaryLockingException but
     *                              we catch and swallow it)
     */
    @Test
    public void testCheckLocksFailsWithSeniorClaimsByOthers() throws InterruptedException, StorageException {
        // Make a pre-existing valid lock by some other tx (written by another process)
        StaticBuffer otherSeniorLockCol = codec.toLockCol(currentTimeNS, otherLockRid);
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);
        // Expect checker to fetch locks for defaultTx; return just our own lock (not the other guy's)
        StaticBuffer ownJuniorLockCol = codec.toLockCol(currentTimeNS, defaultLockRid);
        ConsistentKeyLockStatus ownJuniorLS = makeStatusNow();
        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, ownJuniorLS));

        currentTimeNS += TimeUnit.NANOSECONDS.convert(10, TimeUnit.SECONDS);

        // Return defaultTx's lock in a map when requested
        expectSleepAfterWritingLock(ownJuniorLS);

        // When the checker slices the store, return the senior lock col by a
        // foreign tx and the junior lock col by defaultTx (in that order)
        recordLockGetSlice(StaticArrayEntryList.of(
                StaticArrayEntry.of(otherSeniorLockCol, defaultLockVal),
                StaticArrayEntry.of(ownJuniorLockCol, defaultLockVal)));

        ctrl.replay();

        TemporaryLockingException tle = null;
        try {
            locker.checkLocks(defaultTx);
        } catch (TemporaryLockingException e) {
            tle = e;
        }
        assertNotNull(tle);
    }

    /**
     * When the checker retrieves its own lock column followed by a lock column
     * with a later timestamp (both with unexpired timestamps), it should
     * consider the lock successfully checked.
     *
     * @throws InterruptedException shouldn't happen
     * @throws StorageException     shouldn't happen
     */
    @Test
    public void testCheckLocksSucceedsWithJuniorClaimsByOthers() throws InterruptedException, StorageException {
        // Expect checker to fetch locks for defaultTx; return just our own lock (not the other guy's)
        StaticBuffer ownSeniorLockCol = codec.toLockCol(currentTimeNS, defaultLockRid);
        ConsistentKeyLockStatus ownSeniorLS = makeStatusNow();
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);
        // Make junior lock
        StaticBuffer otherJuniorLockCol = codec.toLockCol(currentTimeNS, otherLockRid);
        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, ownSeniorLS));

        currentTimeNS += TimeUnit.NANOSECONDS.convert(10, TimeUnit.SECONDS);

        // Return defaultTx's lock in a map when requested
        expectSleepAfterWritingLock(ownSeniorLS);

        // When the checker slices the store, return the senior lock col by a
        // foreign tx and the junior lock col by defaultTx (in that order)
        recordLockGetSlice(StaticArrayEntryList.of(
                StaticArrayEntry.of(ownSeniorLockCol, defaultLockVal),
                StaticArrayEntry.of(otherJuniorLockCol, defaultLockVal)));

        ctrl.replay();

        locker.checkLocks(defaultTx);
    }

    /**
     * If the checker retrieves a timestamp-ordered list of columns, where the
     * list starts with an unbroken series of columns with the checker's rid but
     * differing timestamps, then consider the lock successfully checked if the
     * checker's expected timestamp occurs anywhere in that series of columns.
     * <p/>
     * This relaxation of the normal checking rules only triggers when either
     * writeLock(...) issued mutate calls that appeared to fail client-side but
     * which actually succeeded (e.g. hinted handoff or timeout)
     *
     * @throws InterruptedException shouldn't happen
     * @throws StorageException     shouldn't happen
     */
    @Test
    public void testCheckLocksSucceedsWithSeniorAndJuniorClaimsBySelf() throws InterruptedException, StorageException {
        // Setup three lock columns differing only in timestamp
        StaticBuffer myFirstLockCol = codec.toLockCol(currentTimeNS, defaultLockRid);
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);
        StaticBuffer mySecondLockCol = codec.toLockCol(currentTimeNS, defaultLockRid);
        ConsistentKeyLockStatus mySecondLS = makeStatusNow();
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);
        StaticBuffer myThirdLockCol = codec.toLockCol(currentTimeNS, defaultLockRid);
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);

        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, mySecondLS));

        // Return defaultTx's second lock in a map when requested
        currentTimeNS += TimeUnit.NANOSECONDS.convert(10, TimeUnit.SECONDS);
        expectSleepAfterWritingLock(mySecondLS);

        // When the checker slices the store, return the senior lock col by a
        // foreign tx and the junior lock col by defaultTx (in that order)
        recordLockGetSlice(StaticArrayEntryList.of(
                StaticArrayEntry.of(myFirstLockCol, defaultLockVal),
                StaticArrayEntry.of(mySecondLockCol, defaultLockVal),
                StaticArrayEntry.of(myThirdLockCol, defaultLockVal)));

        ctrl.replay();

        locker.checkLocks(defaultTx);
    }

    /**
     * The checker should retry getSlice() in the face of a
     * TemporaryStorageException so long as the number of exceptional
     * getSlice()s is fewer than the lock retry count. The retry count applies
     * on a per-lock basis.
     *
     * @throws StorageException     shouldn't happen
     * @throws InterruptedException shouldn't happen
     */
    @Test
    public void testCheckLocksRetriesAfterSingleTemporaryStorageException() throws StorageException, InterruptedException {
        // Setup one lock column
        StaticBuffer lockCol = codec.toLockCol(currentTimeNS, defaultLockRid);
        ConsistentKeyLockStatus lockStatus = makeStatusNow();
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);

        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, lockStatus));

        expectSleepAfterWritingLock(lockStatus);

        // First getSlice will fail
        TemporaryStorageException tse = new TemporaryStorageException("Storage cluster will be right back");
        recordExceptionalLockGetSlice(tse);

        // Second getSlice will succeed
        recordLockGetSliceAndReturnSingleEntry(StaticArrayEntry.of(lockCol, defaultLockVal));

        ctrl.replay();

        locker.checkLocks(defaultTx);

        // TODO run again with two locks instead of one and show that the retry count applies on a per-lock basis
    }

    /**
     * The checker will throw a TemporaryStorageException if getSlice() throws
     * fails with a TemporaryStorageException as many times as there are
     * configured lock retries.
     *
     * @throws InterruptedException shouldn't happen
     * @throws StorageException     shouldn't happen
     */
    @Test
    public void testCheckLocksThrowsExceptionAfterMaxTemporaryStorageExceptions() throws InterruptedException, StorageException {
        // Setup a LockStatus for defaultLockID
        ConsistentKeyLockStatus lockStatus = makeStatusNow();
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);

        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, lockStatus));

        expectSleepAfterWritingLock(lockStatus);

        // Three successive getSlice calls, each throwing a distinct TSE
        recordExceptionalLockGetSlice(new TemporaryStorageException("Storage cluster is having me-time"));
        recordExceptionalLockGetSlice(new TemporaryStorageException("Storage cluster is in a dissociative fugue state"));
        recordExceptionalLockGetSlice(new TemporaryStorageException("Storage cluster has gone to Prague to find itself"));

        ctrl.replay();

        TemporaryStorageException tse = null;
        try {
            locker.checkLocks(defaultTx);
        } catch (TemporaryStorageException e) {
            tse = e;
        }
        assertNotNull(tse);
    }

    /**
     * A single PermanentStorageException on getSlice() for a single lock is
     * sufficient to make the method return immediately (regardless of whether
     * other locks are waiting to be checked).
     *
     * @throws InterruptedException shouldn't happen
     * @throws StorageException     shouldn't happen
     */
    @Test
    public void testCheckLocksDiesOnPermanentStorageException() throws InterruptedException, StorageException {
        // Setup a LockStatus for defaultLockID
        ConsistentKeyLockStatus lockStatus = makeStatusNow();
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);

        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, lockStatus));

        expectSleepAfterWritingLock(lockStatus);

        // First and only getSlice call throws a PSE
        recordExceptionalLockGetSlice(new PermanentStorageException("Connection to storage cluster failed: peer is an IPv6 toaster"));

        ctrl.replay();

        PermanentStorageException pse = null;
        try {
            locker.checkLocks(defaultTx);
        } catch (PermanentStorageException e) {
            pse = e;
        }
        assertNotNull(pse);
    }

    /**
     * The lock checker should do nothing when passed a transaction for which it
     * holds no locks.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testCheckLocksDoesNothingForUnrecognizedTransaction() throws StorageException {
        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.<KeyColumn, ConsistentKeyLockStatus>of());
        ctrl.replay();
        locker.checkLocks(defaultTx);
    }

    /**
     * Delete a single lock without any timeouts, errors, etc.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testDeleteLocksInSimplestCase() throws StorageException {
        // Setup a LockStatus for defaultLockID
        final ConsistentKeyLockStatus lockStatus = makeStatusNow();
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);

        @SuppressWarnings("serial")
        Map<KeyColumn, ConsistentKeyLockStatus> expectedMap = new HashMap<KeyColumn, ConsistentKeyLockStatus>() {{
            put(defaultLockID, lockStatus);
        }};
        expect(lockState.getLocksForTx(defaultTx)).andReturn(expectedMap);

        List<StaticBuffer> dels = ImmutableList.of(codec.toLockCol(lockStatus.getWriteTimestamp(TimeUnit.NANOSECONDS), defaultLockRid));
        expect(times.getTime()).andReturn(new Timepoint(currentTimeNS, TimeUnit.NANOSECONDS));
        store.mutate(eq(defaultLockKey), eq(ImmutableList.<Entry>of()), eq(dels), eq(defaultTx));
        expect(mediator.unlock(defaultLockID, defaultTx)).andReturn(true);

        ctrl.replay();

        locker.deleteLocks(defaultTx);
    }

    /**
     * Delete two locks without any timeouts, errors, etc.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testDeleteLocksOnTwoLocks() throws StorageException {
        ConsistentKeyLockStatus defaultLS = makeStatusNow();
        currentTimeNS++;
        ConsistentKeyLockStatus otherLS = makeStatusNow();
        currentTimeNS++;

        // Expect a call for defaultTx's locks and return two
        Map<KeyColumn, ConsistentKeyLockStatus> expectedMap = Maps.newLinkedHashMap();
        expectedMap.put(defaultLockID, defaultLS);
        expectedMap.put(otherLockID, otherLS);
        expect(lockState.getLocksForTx(defaultTx)).andReturn(expectedMap);

        expectLockDeleteSuccessfully(defaultLockID, defaultLockKey, defaultLS);

        expectLockDeleteSuccessfully(otherLockID, otherLockKey, otherLS);

        ctrl.replay();

        locker.deleteLocks(defaultTx);
    }

    private void expectLockDeleteSuccessfully(KeyColumn lockID, StaticBuffer lockKey, ConsistentKeyLockStatus lockStatus) throws StorageException {
        expectDeleteLock(lockID, lockKey, lockStatus);
    }

    private void expectDeleteLock(KeyColumn lockID, StaticBuffer lockKey, ConsistentKeyLockStatus lockStatus, StorageException... backendFailures) throws StorageException {
        List<StaticBuffer> dels = ImmutableList.of(codec.toLockCol(lockStatus.getWriteTimestamp(TimeUnit.NANOSECONDS), defaultLockRid));
        expect(times.getTime()).andReturn(new Timepoint(currentTimeNS, TimeUnit.NANOSECONDS));
        store.mutate(eq(lockKey), eq(ImmutableList.<Entry>of()), eq(dels), eq(defaultTx));
        int backendExceptionsThrown = 0;
        for (StorageException e : backendFailures) {
            expectLastCall().andThrow(e);
            if (e instanceof PermanentStorageException) {
                break;
            }
            backendExceptionsThrown++;
            if (backendExceptionsThrown < maxTemporaryStorageExceptions) {
                expect(times.getTime()).andReturn(new Timepoint(currentTimeNS, TimeUnit.NANOSECONDS));
                store.mutate(eq(lockKey), eq(ImmutableList.<Entry>of()), eq(dels), eq(defaultTx));
            }
        }
        expect(mediator.unlock(lockID, defaultTx)).andReturn(true);
    }

    /**
     * Lock deletion should retry if the first store mutation throws a temporary
     * exception.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testDeleteLocksRetriesOnTemporaryStorageException() throws StorageException {
        ConsistentKeyLockStatus defaultLS = makeStatusNow();
        currentTimeNS++;
        expect(lockState.getLocksForTx(defaultTx)).andReturn(Maps.newLinkedHashMap(ImmutableMap.of(defaultLockID, defaultLS)));
        expectDeleteLock(defaultLockID, defaultLockKey, defaultLS, new TemporaryStorageException("Storage cluster is backlogged"));
        ctrl.replay();

        locker.deleteLocks(defaultTx);
    }

    /**
     * If lock deletion exceeds the temporary exception retry count when trying
     * to delete a lock, it should move onto the next lock rather than returning
     * and potentially leaving the remaining locks undeleted.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testDeleteLocksSkipsToNextLockAfterMaxTemporaryStorageExceptions() throws StorageException {
        ConsistentKeyLockStatus defaultLS = makeStatusNow();
        currentTimeNS++;
        expect(lockState.getLocksForTx(defaultTx)).andReturn(Maps.newLinkedHashMap(ImmutableMap.of(defaultLockID, defaultLS)));

        expectDeleteLock(defaultLockID, defaultLockKey, defaultLS,
                new TemporaryStorageException("Storage cluster is busy"),
                new TemporaryStorageException("Storage cluster is busier"),
                new TemporaryStorageException("Storage cluster has reached peak business"));

        ctrl.replay();

        locker.deleteLocks(defaultTx);
    }

    /**
     * Same as
     * {@link #testDeleteLocksSkipsToNextLockAfterMaxTemporaryStorageExceptions()}
     * , except instead of exceeding the temporary exception retry count on a
     * lock, that lock throws a single permanent exception.
     *
     * @throws StorageException shoudn't happen
     */
    @Test
    public void testDeleteLocksSkipsToNextLockOnPermanentStorageException() throws StorageException {
        ConsistentKeyLockStatus defaultLS = makeStatusNow();
        currentTimeNS++;
        expect(lockState.getLocksForTx(defaultTx)).andReturn(Maps.newLinkedHashMap(ImmutableMap.of(defaultLockID, defaultLS)));

        expectDeleteLock(defaultLockID, defaultLockKey, defaultLS, new PermanentStorageException("Storage cluster has been destroyed by a tornado"));

        ctrl.replay();

        locker.deleteLocks(defaultTx);
    }

    /**
     * Deletion should remove previously written locks regardless of whether
     * they were ever checked; this method fakes and verifies deletion on a
     * single unchecked lock
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testDeleteLocksDeletesUncheckedLocks() throws StorageException {
        ConsistentKeyLockStatus defaultLS = makeStatusNow();
        assertFalse(defaultLS.isChecked());
        currentTimeNS++;

        // Expect a call for defaultTx's locks and the checked one
        expect(lockState.getLocksForTx(defaultTx)).andReturn(Maps.newLinkedHashMap(ImmutableMap.of(defaultLockID, defaultLS)));

        expectLockDeleteSuccessfully(defaultLockID, defaultLockKey, defaultLS);

        ctrl.replay();

        locker.deleteLocks(defaultTx);
    }

    /**
     * When delete is called multiple times with no intervening write or check
     * calls, all calls after the first should have no effect.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testDeleteLocksIdempotence() throws StorageException {
        // Setup a LockStatus for defaultLockID
        ConsistentKeyLockStatus lockStatus = makeStatusNow();
        currentTimeNS += TimeUnit.NANOSECONDS.convert(1, TimeUnit.NANOSECONDS);

        expect(lockState.getLocksForTx(defaultTx)).andReturn(Maps.newLinkedHashMap(ImmutableMap.of(defaultLockID, lockStatus)));

        expectLockDeleteSuccessfully(defaultLockID, defaultLockKey, lockStatus);

        ctrl.replay();

        locker.deleteLocks(defaultTx);

        ctrl.verify();
        ctrl.reset();
        expect(lockState.getLocksForTx(defaultTx)).andReturn(Maps.newLinkedHashMap(ImmutableMap.<KeyColumn, ConsistentKeyLockStatus>of()));
        ctrl.replay();
        locker.deleteLocks(defaultTx);
    }

    /**
     * Delete should do nothing when passed a transaction for which it holds no
     * locks.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testDeleteLocksDoesNothingForUnrecognizedTransaction() throws StorageException {
        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.<KeyColumn, ConsistentKeyLockStatus>of());
        ctrl.replay();
        locker.deleteLocks(defaultTx);
    }

    /**
     * Checking locks when the expired lock cleaner is enabled should trigger
     * one call to the LockCleanerService.
     *
     * @throws StorageException shouldn't happen
     */
    @Test
    public void testCleanExpiredLock() throws StorageException, InterruptedException {
        LockCleanerService mockCleaner = ctrl.createMock(LockCleanerService.class);
        expect(times.getUnit()).andReturn(TimeUnit.NANOSECONDS).atLeastOnce();
        ctrl.replay();
        Locker altLocker = getDefaultBuilder().customCleaner(mockCleaner).build();
        ctrl.verify();
        ctrl.reset();

        final ConsistentKeyLockStatus expired = makeStatusNow();
        expect(lockState.getLocksForTx(defaultTx)).andReturn(ImmutableMap.of(defaultLockID, expired));
        currentTimeNS += TimeUnit.NANOSECONDS.convert(100, TimeUnit.DAYS); // pretend a huge multiple of the expiration time has passed

        // Checker should compare the fake lock's timestamp to the current time
        expect(times.sleepPast(new Timepoint(expired.getWriteTimestamp(TimeUnit.NANOSECONDS) + defaultWaitNS, TimeUnit.NANOSECONDS))).andReturn(new Timepoint(currentTimeNS, TimeUnit.NANOSECONDS));

        // Checker must slice the store; we return the single expired lock column
        recordLockGetSliceAndReturnSingleEntry(
                StaticArrayEntry.of(
                        codec.toLockCol(expired.getWriteTimestamp(TimeUnit.NANOSECONDS), defaultLockRid),
                        defaultLockVal));

        // Checker must attempt to cleanup expired lock
        mockCleaner.clean(eq(defaultLockID), eq(currentTimeNS - defaultExpireNS), eq(defaultTx));
        expectLastCall().once();

        ctrl.replay();
        TemporaryLockingException ple = null;
        try {
            altLocker.checkLocks(defaultTx);
        } catch (TemporaryLockingException e) {
            ple = e;
        }
        assertNotNull(ple);
    }

    /*
     * Helpers
     */

    public static class LockInfo {

        private final long tsNS;
        private final ConsistentKeyLockStatus stat;
        private final StaticBuffer col;

        public LockInfo(long tsNS, ConsistentKeyLockStatus stat,
                        StaticBuffer col) {
            this.tsNS = tsNS;
            this.stat = stat;
            this.col = col;
        }
    }

    private ConsistentKeyLockStatus makeStatus(long currentNS) {
        return new ConsistentKeyLockStatus(
                new Timepoint(currentTimeNS, TimeUnit.NANOSECONDS),
                new Timepoint(defaultExpireNS, TimeUnit.NANOSECONDS));
    }

    private ConsistentKeyLockStatus makeStatusNow() {
        return makeStatus(currentTimeNS);
    }

    private LockInfo recordSuccessfulLockWrite(long duration, TimeUnit tu, StaticBuffer del) throws StorageException {
        return recordSuccessfulLockWrite(defaultTx, duration, tu, del);
    }

    private LockInfo recordSuccessfulLockWrite(StoreTransaction tx, long duration, TimeUnit tu, StaticBuffer del) throws StorageException {
        expect(times.getTime()).andReturn(new Timepoint(++currentTimeNS, TimeUnit.NANOSECONDS));

        final long lockNS = currentTimeNS;

        StaticBuffer lockCol = codec.toLockCol(lockNS, defaultLockRid);
        Entry add = StaticArrayEntry.of(lockCol, defaultLockVal);

        StaticBuffer k = eq(defaultLockKey);
//        assert null != add;
        final List<Entry> adds = eq(Arrays.<Entry>asList(add));
//        assert null != adds;
        final List<StaticBuffer> dels;
        if (null != del) {
            dels = eq(Arrays.<StaticBuffer>asList(del));
        } else {
            dels = eq(ImmutableList.<StaticBuffer>of());
        }
        store.mutate(k, adds, dels, eq(tx));

        currentTimeNS += TimeUnit.NANOSECONDS.convert(duration, tu);

        expect(times.getTime()).andReturn(new Timepoint(currentTimeNS, TimeUnit.NANOSECONDS));

        // A Timer instance measures how long the mutate call took
        // Timer's implementation may call TimestampProvider.getUnit()
        expect(times.getUnit()).andReturn(TimeUnit.NANOSECONDS).anyTimes();

        ConsistentKeyLockStatus status = new ConsistentKeyLockStatus(
                new Timepoint(lockNS, TimeUnit.NANOSECONDS),
                new Timepoint(lockNS + defaultExpireNS, TimeUnit.NANOSECONDS));

        return new LockInfo(lockNS, status, lockCol);
    }

    private StaticBuffer recordExceptionLockWrite(long duration, TimeUnit tu, StaticBuffer del, Throwable t) throws StorageException {
        expect(times.getTime()).andReturn(new Timepoint(++currentTimeNS, TimeUnit.NANOSECONDS));

        StaticBuffer lockCol = codec.toLockCol(currentTimeNS, defaultLockRid);
        Entry add = StaticArrayEntry.of(lockCol, defaultLockVal);

        StaticBuffer k = eq(defaultLockKey);
//        assert null != add;
        final List<Entry> adds = eq(Arrays.<Entry>asList(add));
//        assert null != adds;
        final List<StaticBuffer> dels;
        if (null != del) {
            dels = eq(Arrays.<StaticBuffer>asList(del));
        } else {
            dels = eq(ImmutableList.<StaticBuffer>of());
        }
        store.mutate(k, adds, dels, eq(defaultTx));
        expectLastCall().andThrow(t);

        currentTimeNS += TimeUnit.NANOSECONDS.convert(duration, tu);
        expect(times.getTime()).andReturn(new Timepoint(currentTimeNS, TimeUnit.NANOSECONDS));

        // A Timer instance measures how long the mutate call took
        // Timer's implementation may call TimestampProvider.getUnit()
        expect(times.getUnit()).andReturn(TimeUnit.NANOSECONDS).anyTimes();

        return lockCol;
    }

    private void recordSuccessfulLockDelete(long duration, TimeUnit tu, StaticBuffer del) throws StorageException {


        expect(times.getTime()).andReturn(new Timepoint(++currentTimeNS, TimeUnit.NANOSECONDS));
        store.mutate(eq(defaultLockKey), eq(ImmutableList.<Entry>of()), eq(Arrays.asList(del)), eq(defaultTx));

        currentTimeNS += TimeUnit.NANOSECONDS.convert(duration, tu);
        expect(times.getTime()).andReturn(new Timepoint(currentTimeNS, TimeUnit.NANOSECONDS));

        // A Timer instance measures how long the mutate call took
        // Timer's implementation may call TimestampProvider.getUnit()
        expect(times.getUnit()).andReturn(TimeUnit.NANOSECONDS).anyTimes();
    }

    private void recordSuccessfulLocalLock() {
        recordSuccessfulLocalLock(defaultTx);
    }

    private void recordSuccessfulLocalLock(StoreTransaction tx) {
        expect(times.getTime()).andReturn(new Timepoint(++currentTimeNS, TimeUnit.NANOSECONDS));
        expect(mediator.lock(defaultLockID, tx, new Timepoint(currentTimeNS + defaultExpireNS, TimeUnit.NANOSECONDS))).andReturn(true);
    }

    private void recordSuccessfulLocalLock(long ts) {
        recordSuccessfulLocalLock(defaultTx, ts);
    }

    private void recordSuccessfulLocalLock(StoreTransaction tx, long ts) {
        expect(mediator.lock(defaultLockID, tx, new Timepoint(ts + defaultExpireNS, TimeUnit.NANOSECONDS))).andReturn(true);
    }

    private void recordFailedLocalLock() {
        recordFailedLocalLock(defaultTx);
    }

    private void recordFailedLocalLock(StoreTransaction tx) {
        expect(times.getTime()).andReturn(new Timepoint(++currentTimeNS, TimeUnit.NANOSECONDS));
        expect(mediator.lock(defaultLockID, tx, new Timepoint(currentTimeNS + defaultExpireNS, TimeUnit.NANOSECONDS))).andReturn(false);
    }

    private void recordSuccessfulLocalUnlock() {
        expect(mediator.unlock(defaultLockID, defaultTx)).andReturn(true);
    }

    private void recordLockGetSlice(EntryList returnedEntries) throws StorageException {
        final KeySliceQuery ksq = new KeySliceQuery(defaultLockKey, LOCK_COL_START, LOCK_COL_END);
        expect(store.getSlice(eq(ksq), eq(defaultTx))).andReturn(returnedEntries);
    }

    private void recordExceptionalLockGetSlice(Throwable t) throws StorageException {
        final KeySliceQuery ksq = new KeySliceQuery(defaultLockKey, LOCK_COL_START, LOCK_COL_END);
        expect(store.getSlice(eq(ksq), eq(defaultTx))).andThrow(t);
    }

    private void recordLockGetSliceAndReturnSingleEntry(Entry returnSingleEntry) throws StorageException {
        recordLockGetSlice(StaticArrayEntryList.of(returnSingleEntry));
    }

    private ConsistentKeyLocker.Builder getDefaultBuilder() {
        return new ConsistentKeyLocker.Builder(store, manager)
            .times(times)
            .mediator(mediator)
            .internalState(lockState)
            .lockExpire(new SimpleDuration(defaultExpireNS, TimeUnit.NANOSECONDS))
            .lockWait(new SimpleDuration(defaultWaitNS, TimeUnit.NANOSECONDS))
            .rid(defaultLockRid);
    }
}
