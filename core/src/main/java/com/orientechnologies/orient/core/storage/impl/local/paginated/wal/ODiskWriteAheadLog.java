/*
 *
 *  *  Copyright 2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://www.orientechnologies.com
 *
 */

package com.orientechnologies.orient.core.storage.impl.local.paginated.wal;

import com.orientechnologies.common.concur.executors.SubScheduledExecutorService;
import com.orientechnologies.common.concur.lock.OInterruptedException;
import com.orientechnologies.common.exception.OException;
import com.orientechnologies.common.io.OFileUtils;
import com.orientechnologies.common.io.OIOException;
import com.orientechnologies.common.io.OIOUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.common.serialization.types.OLongSerializer;
import com.orientechnologies.common.thread.OScheduledThreadPoolExecutorWithLogging;
import com.orientechnologies.common.util.OUncaughtExceptionHandler;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.exception.OStorageException;
import com.orientechnologies.orient.core.storage.OStorageAbstract;
import com.orientechnologies.orient.core.storage.impl.local.OFullCheckpointRequestListener;
import com.orientechnologies.orient.core.storage.impl.local.OLowDiskSpaceInformation;
import com.orientechnologies.orient.core.storage.impl.local.OLowDiskSpaceListener;
import com.orientechnologies.orient.core.storage.impl.local.paginated.OLocalPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations.OAtomicOperationMetadata;
import com.orientechnologies.orient.core.storage.impl.local.statistic.OPerformanceStatisticManager;
import com.orientechnologies.orient.core.storage.impl.local.statistic.OSessionStoragePerformanceStatistic;

import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.zip.CRC32;

/**
 * @author Andrey Lomakin
 * @since 25.04.13
 */
public class ODiskWriteAheadLog extends OAbstractWriteAheadLog {
  public static final  String MASTER_RECORD_EXTENSION = ".wmr";
  public static final  String WAL_SEGMENT_EXTENSION   = ".wal";
  private static final long   ONE_KB                  = 1024L;

  private final long freeSpaceLimit = OGlobalConfiguration.DISK_CACHE_FREE_SPACE_LIMIT.getValueAsLong() * ONE_KB * ONE_KB;
  private final long walSizeLimit   = OGlobalConfiguration.WAL_MAX_SIZE.getValueAsLong() * ONE_KB * ONE_KB;

  private final List<OLogSegment> logSegments = new ArrayList<OLogSegment>();
  private final int              maxPagesCacheSize;
  private final int              commitDelay;
  private final long             maxSegmentSize;
  private final long             preferredSegmentCount;
  private final File             walLocation;
  private final RandomAccessFile masterRecordLSNHolder;
  private final        List<OLogSequenceNumber> lastTXs      = new LinkedList<OLogSequenceNumber>();
  private final static int                      MAX_LAST_TXS = 16;

  /**
   * If file of {@link OLogSegmentV2} will not be accessed inside of this interval (in seconds) it will be closed by timer.
   */
  private final int fileTTL;

  private final OLocalPaginatedStorage       storage;
  private final OPerformanceStatisticManager performanceStatisticManager;

  private boolean useFirstMasterRecord = true;

  private volatile long               logSize;
  private          File               masterRecordFile;
  private          OLogSequenceNumber firstMasterRecord;
  private          OLogSequenceNumber secondMasterRecord;

  private volatile OLogSequenceNumber flushedLsn;

  private final ConcurrentSkipListMap<OLogSequenceNumber, Integer> cutTillLimits = new ConcurrentSkipListMap<OLogSequenceNumber, Integer>();

  private volatile long cacheOverflowCount = 0;

  private       boolean   segmentCreationFlag     = false;
  private final Condition segmentCreationComplete = syncObject.newCondition();

  private final Set<OOperationUnitId>                               activeOperations        = new HashSet<OOperationUnitId>();
  private final List<WeakReference<OLowDiskSpaceListener>>          lowDiskSpaceListeners   = new CopyOnWriteArrayList<WeakReference<OLowDiskSpaceListener>>();
  private final List<WeakReference<OFullCheckpointRequestListener>> fullCheckpointListeners = new CopyOnWriteArrayList<WeakReference<OFullCheckpointRequestListener>>();

  private static class SimpleFileNameFilter implements java.io.FilenameFilter {
    private final Locale locale;

    SimpleFileNameFilter(OLocalPaginatedStorage storage) {
      this.locale = storage.getConfiguration().getLocaleInstance();
    }

    @Override
    public boolean accept(File dir, String name) {
      return validateSimpleName(name, locale);
    }
  }

  private final ScheduledThreadPoolExecutor autoFileCloser = new OScheduledThreadPoolExecutorWithLogging(1, new ThreadFactory() {
    @SuppressWarnings("NullableProblems")
    @Override
    public Thread newThread(Runnable r) {
      final Thread thread = new Thread(OStorageAbstract.storageThreadGroup, r);
      thread.setDaemon(true);
      thread.setName("WAL Closer Task (" + getStorage().getName() + ")");
      thread.setUncaughtExceptionHandler(new OUncaughtExceptionHandler());
      return thread;
    }
  });

  private final ScheduledThreadPoolExecutor commitExecutor = new OScheduledThreadPoolExecutorWithLogging(1, new ThreadFactory() {
    @SuppressWarnings("NullableProblems")
    @Override
    public Thread newThread(Runnable r) {
      final Thread thread = new Thread(OStorageAbstract.storageThreadGroup, r);
      thread.setDaemon(true);
      thread.setName("OrientDB WAL Flush Task (" + getStorage().getName() + ")");
      thread.setUncaughtExceptionHandler(new OUncaughtExceptionHandler());
      return thread;
    }
  });

  private static class FilenameFilter implements java.io.FilenameFilter {
    private final String storageName;
    private final Locale locale;

    FilenameFilter(OLocalPaginatedStorage storage) {
      this.storageName = storage.getName();
      this.locale = storage.getConfiguration().getLocaleInstance();
    }

    @Override
    public boolean accept(File dir, String name) {
      return validateName(name, storageName, locale);
    }
  }

  public ODiskWriteAheadLog(OLocalPaginatedStorage storage) throws IOException {
    this(OGlobalConfiguration.WAL_CACHE_SIZE.getValueAsInteger(), OGlobalConfiguration.WAL_COMMIT_TIMEOUT.getValueAsInteger(),
        OGlobalConfiguration.WAL_MAX_SEGMENT_SIZE.getValueAsInteger() * ONE_KB * ONE_KB,
        OGlobalConfiguration.WAL_LOCATION.getValueAsString(), true, storage,
        OGlobalConfiguration.WAL_FILE_AUTOCLOSE_INTERVAL.getValueAsInteger());
  }

  @Override
  public void addLowDiskSpaceListener(OLowDiskSpaceListener listener) {
    lowDiskSpaceListeners.add(new WeakReference<OLowDiskSpaceListener>(listener));
  }

  @Override
  public void removeLowDiskSpaceListener(OLowDiskSpaceListener listener) {
    List<WeakReference<OLowDiskSpaceListener>> itemsToRemove = new ArrayList<WeakReference<OLowDiskSpaceListener>>();

    for (WeakReference<OLowDiskSpaceListener> ref : lowDiskSpaceListeners) {
      final OLowDiskSpaceListener lowDiskSpaceListener = ref.get();

      if (lowDiskSpaceListener == null || lowDiskSpaceListener.equals(listener))
        itemsToRemove.add(ref);
    }

    lowDiskSpaceListeners.removeAll(itemsToRemove);
  }

  @Override
  public void addFullCheckpointListener(OFullCheckpointRequestListener listener) {
    fullCheckpointListeners.add(new WeakReference<OFullCheckpointRequestListener>(listener));
  }

  @Override
  public void removeFullCheckpointListener(OFullCheckpointRequestListener listener) {
    List<WeakReference<OFullCheckpointRequestListener>> itemsToRemove = new ArrayList<WeakReference<OFullCheckpointRequestListener>>();

    for (WeakReference<OFullCheckpointRequestListener> ref : fullCheckpointListeners) {
      final OFullCheckpointRequestListener fullCheckpointRequestListener = ref.get();

      if (fullCheckpointRequestListener == null || fullCheckpointRequestListener.equals(listener))
        itemsToRemove.add(ref);
    }

    fullCheckpointListeners.removeAll(itemsToRemove);
  }

  /**
   * @param fileTTL If file of {@link OLogSegmentV2} will not be accessed inside of this interval (in seconds) it will be closed by
   *                timer.
   */
  public ODiskWriteAheadLog(int maxPagesCacheSize, int commitDelay, long maxSegmentSize, final String walPath,
      boolean filterWALFiles, final OLocalPaginatedStorage storage, int fileTTL) throws IOException {
    this.fileTTL = fileTTL;
    this.maxPagesCacheSize = maxPagesCacheSize;
    this.commitDelay = commitDelay;
    this.maxSegmentSize = maxSegmentSize;
    this.preferredSegmentCount = walSizeLimit / maxSegmentSize;
    this.storage = storage;
    this.performanceStatisticManager = storage.getPerformanceStatisticManager();

    try {
      this.walLocation = new File(calculateWalPath(this.storage, walPath));

      File[] walFiles;
      if (filterWALFiles)
        walFiles = this.walLocation.listFiles(new FilenameFilter(storage));
      else
        walFiles = this.walLocation.listFiles(new SimpleFileNameFilter(storage));

      if (walFiles == null)
        throw new IllegalStateException(
            "Location passed in WAL does not exist, or IO error was happened. DB cannot work in durable mode in such case");

      if (walFiles.length == 0) {

        OLogSegment logSegment = new OLogSegmentV2(this, new File(this.walLocation, getSegmentName(0)), fileTTL, maxPagesCacheSize,
            performanceStatisticManager, new SubScheduledExecutorService(autoFileCloser),
            new SubScheduledExecutorService(commitExecutor));
        logSegment.init();
        logSegment.startBackgroundWrite();
        logSegments.add(logSegment);

        logSize = 0;

        flushedLsn = null;
      } else {

        logSize = 0;

        for (File walFile : walFiles) {
          final RandomAccessFile st = new RandomAccessFile(walFile, "r");
          final FileChannel channel = st.getChannel();

          OLogSegment logSegment;

          //if WAL segment is broken always use latest version
          if (channel.size() / OWALPage.PAGE_SIZE < 1) {
            channel.close();
            st.close();

            logSegment = new OLogSegmentV2(this, walFile, fileTTL, maxPagesCacheSize, performanceStatisticManager,
                new SubScheduledExecutorService(autoFileCloser), new SubScheduledExecutorService(commitExecutor));
          } else {

            //magic number is used not only to check data integrity but also
            //to check version of binary format is used for log segment.
            final ByteBuffer buffer = ByteBuffer.allocate(OLongSerializer.LONG_SIZE).order(ByteOrder.nativeOrder());
            OIOUtils.readByteBuffer(buffer, channel, OWALPage.MAGIC_NUMBER_OFFSET, false);

            channel.close();
            st.close();

            final long magicNumber = buffer.getLong(0);

            if (magicNumber == OWALPageV1.MAGIC_NUMBER) {
              logSegment = new OLogSegmentV1(this, walFile, fileTTL, maxPagesCacheSize, performanceStatisticManager,
                  new SubScheduledExecutorService(autoFileCloser), new SubScheduledExecutorService(commitExecutor));
            } else {
              logSegment = new OLogSegmentV2(this, walFile, fileTTL, maxPagesCacheSize, performanceStatisticManager,
                  new SubScheduledExecutorService(autoFileCloser), new SubScheduledExecutorService(commitExecutor));
            }
          }

          logSegment.init();

          logSegments.add(logSegment);
          logSize += logSegment.filledUpTo();
        }

        Collections.sort(logSegments);

        OLogSegment last = logSegments.get(logSegments.size() - 1);

        //if last segment is created under older version, create new one
        if (last instanceof OLogSegmentV1) {
          OLogManager.instance()
              .info(this, "Out of dated binary format for WAL segment `%s` in storage `%s` is detected, new segment will be added ",
                  last.getPath(), storage.getName());

          last = new OLogSegmentV2(this, new File(walLocation, getSegmentName(last.getOrder() + 1)), fileTTL, maxPagesCacheSize,
              performanceStatisticManager, new SubScheduledExecutorService(autoFileCloser),
              new SubScheduledExecutorService(commitExecutor));
          last.init();
          logSegments.add(last);
        }

        last.startBackgroundWrite();

        flushedLsn = findFlushedLSN();
      }

      masterRecordFile = new File(walLocation, this.storage.getName() + MASTER_RECORD_EXTENSION);
      masterRecordLSNHolder = new RandomAccessFile(masterRecordFile, "rws");

      if (masterRecordLSNHolder.length() > 0) {
        firstMasterRecord = readMasterRecord(this.storage.getName(), 0);
        secondMasterRecord = readMasterRecord(this.storage.getName(), 1);

        if (firstMasterRecord == null) {
          useFirstMasterRecord = true;
          lastCheckpoint = secondMasterRecord;
        } else if (secondMasterRecord == null) {
          useFirstMasterRecord = false;
          lastCheckpoint = firstMasterRecord;
        } else {
          if (firstMasterRecord.compareTo(secondMasterRecord) >= 0) {
            lastCheckpoint = firstMasterRecord;
            useFirstMasterRecord = false;
          } else {
            lastCheckpoint = secondMasterRecord;
            useFirstMasterRecord = true;
          }
        }
      }

      fixMasterRecords();

    } catch (FileNotFoundException e) {
      // never happened
      OLogManager.instance().error(this, "Error during file initialization for storage '%s'", e, this.storage.getName());
      throw new IllegalStateException("Error during file initialization for storage '" + this.storage.getName() + "'", e);
    }
  }

  void incrementCacheOverflowCount() {
    cacheOverflowCount++;
  }

  public long getCacheOverflowCount() {
    return cacheOverflowCount;
  }

  private String calculateWalPath(OLocalPaginatedStorage storage, String walPath) {
    if (walPath == null)
      return storage.getStoragePath();

    return walPath;
  }

  private String getSegmentName(long order) {
    return storage.getName() + "." + order + WAL_SEGMENT_EXTENSION;
  }

  private static boolean validateName(String name, String storageName, Locale locale) {
    name = name.toLowerCase(locale);
    storageName = storageName.toLowerCase(locale);

    if (!name.endsWith(".wal"))
      return false;

    int walOrderStartIndex = name.indexOf('.');
    if (walOrderStartIndex == name.length() - 4)
      return false;

    final String walStorageName = name.substring(0, walOrderStartIndex);
    if (!storageName.equals(walStorageName))
      return false;

    int walOrderEndIndex = name.indexOf('.', walOrderStartIndex + 1);

    String walOrder = name.substring(walOrderStartIndex + 1, walOrderEndIndex);
    try {
      //noinspection ResultOfMethodCallIgnored
      Integer.parseInt(walOrder);
    } catch (NumberFormatException ignore) {
      return false;
    }

    return true;
  }

  private static boolean validateSimpleName(String name, Locale locale) {
    name = name.toLowerCase(locale);
    if (!name.endsWith(".wal"))
      return false;

    int walOrderStartIndex = name.indexOf('.');
    if (walOrderStartIndex == name.length() - 4)
      return false;

    final int walOrderEndIndex = name.indexOf('.', walOrderStartIndex + 1);

    String walOrder = name.substring(walOrderStartIndex + 1, walOrderEndIndex);
    try {
      //noinspection ResultOfMethodCallIgnored
      Integer.parseInt(walOrder);
    } catch (NumberFormatException ignore) {
      return false;
    }

    return true;
  }

  File getWalLocation() {
    return walLocation;
  }

  @Override
  public OLogSequenceNumber begin() throws IOException {
    syncObject.lock();
    try {
      checkForClose();

      OLogSegment first = logSegments.get(0);

      if (first.filledUpTo() > 0)
        return first.begin();

      int index = 1;

      while (index < logSegments.size()) {
        first = logSegments.get(index);

        if (first.filledUpTo() > 0)
          return first.begin();

        index++;
      }

      return null;

    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public OLogSequenceNumber end() {
    syncObject.lock();
    try {
      checkForClose();

      int lastIndex = logSegments.size() - 1;
      OLogSegment last = logSegments.get(lastIndex);

      while (last.filledUpTo() == 0) {
        lastIndex--;
        if (lastIndex >= 0)
          last = logSegments.get(lastIndex);
        else
          return null;
      }

      return last.end();
    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public void flush() {
    OLogSegment last;

    syncObject.lock();
    try {
      checkForClose();

      last = logSegments.get(logSegments.size() - 1);
    } finally {
      syncObject.unlock();
    }

    last.flush();
  }

  @Override
  public OLogSequenceNumber logAtomicOperationStartRecord(boolean isRollbackSupported, OOperationUnitId unitId) throws IOException {
    final OSessionStoragePerformanceStatistic statistic = performanceStatisticManager.getSessionPerformanceStatistic();

    if (statistic != null)
      statistic.startWALLogRecordTimer();
    try {
      OAtomicUnitStartRecord record = new OAtomicUnitStartRecord(isRollbackSupported, unitId);
      byte[] content = OWALRecordsFactory.INSTANCE.toStream(record);
      syncObject.lock();
      try {
        checkForClose();

        final OLogSequenceNumber lsn = internalLog(record, content);
        activeOperations.add(unitId);
        return lsn;
      } finally {
        syncObject.unlock();
      }
    } finally {
      if (statistic != null)
        statistic.stopWALRecordTimer(true, false);
    }
  }

  @Override
  public OLogSequenceNumber logAtomicOperationEndRecord(OOperationUnitId operationUnitId, boolean rollback,
      OLogSequenceNumber startLsn, Map<String, OAtomicOperationMetadata<?>> atomicOperationMetadata) throws IOException {
    final OSessionStoragePerformanceStatistic statistic = performanceStatisticManager.getSessionPerformanceStatistic();

    if (statistic != null)
      statistic.startWALLogRecordTimer();
    try {
      OAtomicUnitEndRecord record = new OAtomicUnitEndRecord(operationUnitId, rollback, atomicOperationMetadata);
      byte[] content = OWALRecordsFactory.INSTANCE.toStream(record);
      syncObject.lock();
      try {
        checkForClose();

        final OLogSequenceNumber lsn = internalLog(record, content);
        activeOperations.remove(operationUnitId);

        return lsn;
      } finally {
        syncObject.unlock();
      }
    } finally {
      if (statistic != null)
        statistic.stopWALRecordTimer(false, true);
    }
  }

  @Override
  public OLogSequenceNumber log(final OWALRecord record) throws IOException {
    final OSessionStoragePerformanceStatistic statistic = performanceStatisticManager.getSessionPerformanceStatistic();
    if (statistic != null)
      statistic.startWALLogRecordTimer();
    try {
      return internalLog(record, OWALRecordsFactory.INSTANCE.toStream(record));
    } finally {
      if (statistic != null)
        statistic.stopWALRecordTimer(false, false);
    }

  }

  /**
   * it log a record getting the serialized content as parameter.
   */
  private OLogSequenceNumber internalLog(final OWALRecord record, final byte[] recordContent) throws IOException {
    syncObject.lock();
    try {
      checkForClose();

      if (segmentCreationFlag && record instanceof OOperationUnitRecord && !activeOperations
          .contains(((OOperationUnitRecord) record).getOperationUnitId())) {
        while (segmentCreationFlag) {
          try {
            segmentCreationComplete.await();
          } catch (InterruptedException e) {
            throw OException.wrapException(new OInterruptedException("Segment creation was interrupted"), e);
          }
        }
      }

      OLogSegment last = logSegments.get(logSegments.size() - 1);
      long lastSize = last.filledUpTo();

      final OLogSequenceNumber lsn = last.logRecord(recordContent);
      record.setLsn(lsn);

      if (record.isUpdateMasterRecord()) {
        lastCheckpoint = lsn;
        if (useFirstMasterRecord) {
          firstMasterRecord = lsn;
          writeMasterRecord(0, firstMasterRecord);
          useFirstMasterRecord = false;
        } else {
          secondMasterRecord = lsn;
          writeMasterRecord(1, secondMasterRecord);
          useFirstMasterRecord = true;
        }
      }

      final long sizeDiff = last.filledUpTo() - lastSize;
      logSize += sizeDiff;

      if (last.filledUpTo() >= maxSegmentSize) {
        segmentCreationFlag = true;

        if (record instanceof OAtomicUnitEndRecord && activeOperations.size() == 1 || (!(record instanceof OOperationUnitRecord)
            && activeOperations.isEmpty())) {
          appendNewSegment(last);

          segmentCreationFlag = false;
          segmentCreationComplete.signalAll();
        }
      }

      if (logSize > walSizeLimit && logSegments.size() > 1) {
        for (WeakReference<OFullCheckpointRequestListener> listenerWeakReference : fullCheckpointListeners) {
          final OFullCheckpointRequestListener listener = listenerWeakReference.get();
          if (listener != null)
            listener.requestCheckpoint();
        }
      }

      if (record instanceof OAtomicUnitEndRecord) {
        if (lastTXs.size() > MAX_LAST_TXS)
          lastTXs.remove(0);
        lastTXs.add(lsn);
      }

      return lsn;

    } finally {
      syncObject.unlock();
    }
  }

  private void appendNewSegment(OLogSegment last) throws IOException {
    last.stopBackgroundWrite(true);

    last = new OLogSegmentV2(this, new File(walLocation, getSegmentName(last.getOrder() + 1)), fileTTL, maxPagesCacheSize,
        performanceStatisticManager, new SubScheduledExecutorService(autoFileCloser),
        new SubScheduledExecutorService(commitExecutor));
    last.init();
    last.startBackgroundWrite();

    logSegments.add(last);
  }

  @Override
  public OLogSequenceNumber getOldestTxLsn() {
    if (!lastTXs.isEmpty())
      return lastTXs.get(0);

    return end();
  }

  @Override
  public void moveLsnAfter(OLogSequenceNumber lsn) throws IOException {
    syncObject.lock();
    try {
      if (!activeOperations.isEmpty())
        throw new OStorageException("Can not change end of WAL because there are active atomic operations in the log.");

      if (end() == null)
        throw new OStorageException("Can not change end of WAL because WAL is empty");

      if (end().compareTo(lsn) > 0)
        return;

      OLogSegment last = logSegments.get(logSegments.size() - 1);
      last.stopBackgroundWrite(true);

      if (last.filledUpTo() == 0) {
        last.delete();
        logSegments.remove(logSegments.size() - 1);
      }

      last = new OLogSegmentV2(this, new File(walLocation, getSegmentName(lsn.getSegment() + 1)), fileTTL, maxPagesCacheSize,
          performanceStatisticManager, new SubScheduledExecutorService(autoFileCloser),
          new SubScheduledExecutorService(commitExecutor));
      last.init();
      last.startBackgroundWrite();

      logSegments.add(last);

    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public long activeSegment() {
    syncObject.lock();
    try {
      final OLogSegment last = logSegments.get(logSegments.size() - 1);
      return last.getOrder();
    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public File[] nonActiveSegments(long fromSegment) {
    final List<File> result = new ArrayList<File>();

    syncObject.lock();
    try {
      for (int i = 0; i < logSegments.size() - 1; i++) {
        final OLogSegment logSegment = logSegments.get(i);

        if (logSegment.getOrder() >= fromSegment) {
          final File fileLog = new File(logSegment.getPath());
          result.add(fileLog);
        }
      }
    } finally {
      syncObject.unlock();
    }

    File[] files = new File[result.size()];
    files = result.toArray(files);

    return files;
  }

  public long size() {
    return logSize;
  }

  public List<String> getWalFiles() {
    final ArrayList<String> result = new ArrayList<String>();
    syncObject.lock();
    try {
      for (OLogSegment segment : logSegments) {
        result.add(segment.getPath());
      }
    } finally {
      syncObject.unlock();
    }

    return result;
  }

  public String getWMRFile() {
    syncObject.lock();
    try {
      return masterRecordFile.getAbsolutePath();
    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public void truncate() throws IOException {
    syncObject.lock();
    try {
      if (logSegments.size() < 2)
        return;

      ListIterator<OLogSegment> iterator = logSegments.listIterator(logSegments.size() - 1);
      while (iterator.hasPrevious()) {
        final OLogSegment logSegment = iterator.previous();
        logSegment.delete();
        iterator.remove();
      }

      recalculateLogSize();
    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public void close() throws IOException {
    close(true);
  }

  @Override
  public void close(boolean flush) throws IOException {
    syncObject.lock();
    try {
      if (closed)
        return;

      closed = true;

      cutTillLimits.clear();

      for (OLogSegment logSegment : logSegments)
        logSegment.close(flush);

      if (!commitExecutor.isShutdown()) {
        commitExecutor.shutdown();
        try {
          if (!commitExecutor
              .awaitTermination(OGlobalConfiguration.WAL_SHUTDOWN_TIMEOUT.getValueAsInteger(), TimeUnit.MILLISECONDS))
            throw new OStorageException("WAL flush task for '" + getStorage().getName() + "' storage cannot be stopped");

        } catch (InterruptedException e) {
          OLogManager.instance().error(this, "Cannot shutdown background WAL commit thread", e);
        }
      }

      if (!autoFileCloser.isShutdown()) {
        autoFileCloser.shutdown();
        try {
          if (!autoFileCloser
              .awaitTermination(OGlobalConfiguration.WAL_SHUTDOWN_TIMEOUT.getValueAsInteger(), TimeUnit.MILLISECONDS))
            throw new OStorageException("WAL file auto close tasks '" + getStorage().getName() + "' storage cannot be stopped");

        } catch (InterruptedException e) {
          OLogManager.instance().error(this, "Shutdown of file auto close tasks was interrupted", e);
        }
      }

      masterRecordLSNHolder.close();
    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public void delete() throws IOException {
    delete(false);
  }

  @Override
  public void delete(boolean flush) throws IOException {
    syncObject.lock();
    try {
      close(flush);

      for (OLogSegment logSegment : logSegments)
        logSegment.delete();

      boolean deleted = OFileUtils.delete(masterRecordFile);
      int retryCount = 0;

      while (!deleted) {
        deleted = OFileUtils.delete(masterRecordFile);
        retryCount++;

        if (retryCount > 10)
          throw new IOException("Cannot delete file. Retry limit exceeded. (" + retryCount + ")");
      }
    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public OWALRecord read(OLogSequenceNumber lsn) throws IOException {
    syncObject.lock();
    try {
      checkForClose();

      long segment = lsn.getSegment();
      int index = (int) (segment - logSegments.get(0).getOrder());

      if (index < 0 || index >= logSegments.size())
        return null;

      OLogSegment logSegment = logSegments.get(index);
      byte[] recordEntry = logSegment.readRecord(lsn);
      if (recordEntry == null)
        return null;

      final OWALRecord record = OWALRecordsFactory.INSTANCE.fromStream(recordEntry);
      record.setLsn(lsn);

      return record;

    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public OLogSequenceNumber next(OLogSequenceNumber lsn) throws IOException {
    syncObject.lock();
    try {
      checkForClose();

      long order = lsn.getSegment();
      int index = (int) (order - logSegments.get(0).getOrder());

      if (index < 0 || index >= logSegments.size())
        return null;

      OLogSegment logSegment = logSegments.get(index);
      OLogSequenceNumber nextLSN = logSegment.getNextLSN(lsn);

      while (nextLSN == null) {
        index++;

        if (index >= logSegments.size())
          return null;

        OLogSegment nextSegment = logSegments.get(index);
        if (nextSegment.filledUpTo() == 0)
          continue;

        nextLSN = nextSegment.begin();
      }

      return nextLSN;
    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public OLogSequenceNumber getFlushedLsn() {
    return flushedLsn;
  }

  @Override
  public boolean cutTill(OLogSequenceNumber lsn) throws IOException {
    syncObject.lock();
    try {
      checkForClose();

      flush();

      final Map.Entry<OLogSequenceNumber, Integer> firsEntry = cutTillLimits.firstEntry();

      if (firsEntry != null && lsn.compareTo(firsEntry.getKey()) > 0)
        lsn = firsEntry.getKey();

      int lastTruncateIndex = -1;

      for (int i = 0; i < logSegments.size() - 1; i++) {
        final OLogSegment logSegment = logSegments.get(i);

        if (logSegment.end().compareTo(lsn) < 0)
          lastTruncateIndex = i;
        else
          break;
      }

      for (int i = 0; i <= lastTruncateIndex; i++) {
        final OLogSegment logSegment = removeHeadSegmentFromList();
        if (logSegment != null)
          logSegment.delete();
      }

      recalculateLogSize();
      fixMasterRecords();

      return lastTruncateIndex != -1;
    } finally {
      syncObject.unlock();
    }
  }

  @Override
  public void addCutTillLimit(OLogSequenceNumber lsn) {
    if (lsn == null)
      throw new NullPointerException();

    while (true) {
      final Integer oldCounter = cutTillLimits.get(lsn);

      final Integer newCounter;

      if (oldCounter == null) {
        if (cutTillLimits.putIfAbsent(lsn, 1) == null)
          break;
      } else {
        newCounter = oldCounter + 1;

        if (cutTillLimits.replace(lsn, oldCounter, newCounter)) {
          break;
        }
      }
    }
  }

  @Override
  public void removeCutTillLimit(OLogSequenceNumber lsn) {
    if (lsn == null)
      throw new NullPointerException();

    while (true) {
      final Integer oldCounter = cutTillLimits.get(lsn);

      if (oldCounter == null)
        throw new IllegalArgumentException(String.format("Limit %s is going to be removed but it was not added", lsn));

      final Integer newCounter = oldCounter - 1;
      if (cutTillLimits.replace(lsn, oldCounter, newCounter)) {
        if (newCounter == 0) {
          cutTillLimits.remove(lsn, newCounter);
        }

        break;
      }
    }
  }

  @Override
  public long getPreferredSegmentCount() {
    return preferredSegmentCount;
  }

  private OLogSegment removeHeadSegmentFromList() {
    if (logSegments.size() < 2)
      return null;

    return logSegments.remove(0);
  }

  private void recalculateLogSize() {
    logSize = 0;

    for (OLogSegment segment : logSegments)
      logSize += segment.filledUpTo();
  }

  private void fixMasterRecords() throws IOException {
    if (firstMasterRecord != null) {
      int index = (int) (firstMasterRecord.getSegment() - logSegments.get(0).getOrder());
      if (logSegments.size() <= index || index < 0) {
        firstMasterRecord = null;
      } else {
        OLogSegment firstMasterRecordSegment = logSegments.get(index);
        if (firstMasterRecordSegment.filledUpTo() <= firstMasterRecord.getPosition())
          firstMasterRecord = null;
      }
    }

    if (secondMasterRecord != null) {
      int index = (int) (secondMasterRecord.getSegment() - logSegments.get(0).getOrder());
      if (logSegments.size() <= index || index < 0) {
        secondMasterRecord = null;
      } else {
        OLogSegment secondMasterRecordSegment = logSegments.get(index);
        if (secondMasterRecordSegment.filledUpTo() <= secondMasterRecord.getPosition())
          secondMasterRecord = null;
      }
    }

    if (firstMasterRecord != null && secondMasterRecord != null)
      return;

    if (firstMasterRecord == null && secondMasterRecord == null) {
      masterRecordLSNHolder.setLength(0);
      masterRecordLSNHolder.getFD().sync();
      lastCheckpoint = null;
    } else {
      if (secondMasterRecord == null)
        secondMasterRecord = firstMasterRecord;
      else
        firstMasterRecord = secondMasterRecord;

      lastCheckpoint = firstMasterRecord;

      writeMasterRecord(0, firstMasterRecord);
      writeMasterRecord(1, secondMasterRecord);
    }
  }

  private OLogSequenceNumber readMasterRecord(String storageName, int index) throws IOException {
    final CRC32 crc32 = new CRC32();
    try {
      masterRecordLSNHolder.seek(index * (OIntegerSerializer.INT_SIZE + 2L * OLongSerializer.LONG_SIZE));

      int firstCRC = masterRecordLSNHolder.readInt();
      final long segment = masterRecordLSNHolder.readLong();
      final long position = masterRecordLSNHolder.readLong();

      byte[] serializedLSN = new byte[2 * OLongSerializer.LONG_SIZE];
      OLongSerializer.INSTANCE.serializeLiteral(segment, serializedLSN, 0);
      OLongSerializer.INSTANCE.serializeLiteral(position, serializedLSN, OLongSerializer.LONG_SIZE);
      crc32.update(serializedLSN);

      if (firstCRC != ((int) crc32.getValue())) {
        OLogManager.instance()
            .error(this, "Cannot restore %d WAL master record for storage %s crc check is failed", null, index, storageName);
        return null;
      }

      return new OLogSequenceNumber(segment, position);
    } catch (EOFException eofException) {
      OLogManager.instance().debug(this, "Cannot restore %d WAL master record for storage %s", eofException, index, storageName);
      return null;
    }
  }

  private void writeMasterRecord(int index, OLogSequenceNumber masterRecord) throws IOException {
    masterRecordLSNHolder.seek(index * (OIntegerSerializer.INT_SIZE + 2L * OLongSerializer.LONG_SIZE));
    final CRC32 crc32 = new CRC32();

    final byte[] serializedLSN = new byte[2 * OLongSerializer.LONG_SIZE];
    OLongSerializer.INSTANCE.serializeLiteral(masterRecord.getSegment(), serializedLSN, 0);
    OLongSerializer.INSTANCE.serializeLiteral(masterRecord.getPosition(), serializedLSN, OLongSerializer.LONG_SIZE);
    crc32.update(serializedLSN);

    byte[] record = new byte[OIntegerSerializer.INT_SIZE + 2 * OLongSerializer.LONG_SIZE];

    OIntegerSerializer.INSTANCE.serializeLiteral((int) crc32.getValue(), record, 0);
    OLongSerializer.INSTANCE.serializeLiteral(masterRecord.getSegment(), record, OIntegerSerializer.INT_SIZE);
    OLongSerializer.INSTANCE
        .serializeLiteral(masterRecord.getPosition(), record, OIntegerSerializer.INT_SIZE + OLongSerializer.LONG_SIZE);
    masterRecordLSNHolder.write(record);
  }

  private OLogSequenceNumber findFlushedLSN() {
    int segment = logSegments.size() - 1;
    while (segment >= 0) {
      final OLogSegment logSegment = logSegments.get(segment);
      final OLogSequenceNumber flushedLSN = logSegment.end();

      if (flushedLSN == null)
        segment--;
      else
        return flushedLSN;
    }

    return null;
  }

  private OLocalPaginatedStorage getStorage() {
    return storage;
  }

  void setFlushedLsn(OLogSequenceNumber flushedLsn) {
    this.flushedLsn = flushedLsn;
  }

  public void checkFreeSpace() {
    final long freeSpace = walLocation.getFreeSpace();
    //system has unlimited amount of free space
    if (freeSpace < 0)
      return;

    if (freeSpace < freeSpaceLimit) {
      for (WeakReference<OLowDiskSpaceListener> listenerWeakReference : lowDiskSpaceListeners) {
        final OLowDiskSpaceListener lowDiskSpaceListener = listenerWeakReference.get();

        if (lowDiskSpaceListener != null)
          lowDiskSpaceListener.lowDiskSpace(new OLowDiskSpaceInformation(freeSpace, freeSpaceLimit));
      }
    }
  }

  int getCommitDelay() {
    return commitDelay;
  }

  /**
   * @inheritDoc
   */
  @Override
  public boolean appendNewSegment() {
    syncObject.lock();
    try {
      if (!activeOperations.isEmpty())
        throw new OStorageException("Can not change end of WAL because there are active atomic operations in the log.");

      if (end() == null)
        return false; //nothing to do next records will be in new segment

      OLogSegment last = logSegments.get(logSegments.size() - 1);
      if (last.filledUpTo() == 0) {
        return false; //nothing to do next records will be in new segment
      }

      appendNewSegment(last);
      return true;
    } catch (IOException ioe) {
      throw OException.wrapException(new OIOException("Error during appending of new segment to the WAL"), ioe);
    } finally {
      syncObject.unlock();
    }
  }
}
