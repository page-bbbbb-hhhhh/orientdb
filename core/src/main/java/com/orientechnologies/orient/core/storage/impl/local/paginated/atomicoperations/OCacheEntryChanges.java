package com.orientechnologies.orient.core.storage.impl.local.paginated.atomicoperations;

import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.cache.OCachePointer;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OLogSequenceNumber;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OWALChanges;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.OWALPageChangesPortion;

/**
 * Created by tglman on 23/06/16.
 */
public class OCacheEntryChanges implements OCacheEntry {

  /**
   * Value of LSN of page before it was modified. LSN of new page equals to (0, 0)
   */
  OLogSequenceNumber originalLsn = new OLogSequenceNumber(0, 0);

  OCacheEntry delegate;
  final OWALChanges changes = new OWALPageChangesPortion();
  OLogSequenceNumber lsn     = null;
  boolean            isNew   = false;
  boolean            pinPage = false;

  public OCacheEntryChanges(OCacheEntry entry) {
    delegate = entry;
  }

  @SuppressWarnings("WeakerAccess")
  public OCacheEntryChanges() {
  }

  @Override
  public void markDirty() {
    delegate.markDirty();
  }

  @Override
  public void clearDirty() {
    delegate.clearDirty();
  }

  @Override
  public boolean isDirty() {
    return delegate.isDirty();
  }

  @Override
  public OCachePointer getCachePointer() {
    return delegate.getCachePointer();
  }

  @Override
  public void clearCachePointer() {
    delegate.clearCachePointer();
  }

  @Override
  public void setCachePointer(OCachePointer cachePointer) {
    delegate.setCachePointer(cachePointer);
  }

  @Override
  public long getFileId() {
    return delegate.getFileId();
  }

  @Override
  public long getPageIndex() {
    return delegate.getPageIndex();
  }

  @Override
  public void acquireExclusiveLock() {
    delegate.acquireExclusiveLock();
  }

  @Override
  public void releaseExclusiveLock() {
    delegate.releaseExclusiveLock();
  }

  @Override
  public void acquireSharedLock() {
    delegate.acquireSharedLock();
  }

  @Override
  public void releaseSharedLock() {
    delegate.releaseSharedLock();
  }

  @Override
  public int getUsagesCount() {
    return delegate.getUsagesCount();
  }

  @Override
  public void incrementUsages() {
    delegate.incrementUsages();
  }

  @Override
  public boolean isLockAcquiredByCurrentThread() {
    return delegate.isLockAcquiredByCurrentThread();
  }

  @Override
  public void decrementUsages() {
    delegate.decrementUsages();
  }

  @Override
  public OWALChanges getChanges() {
    return changes;
  }

  public void setDelegate(OCacheEntry delegate) {
    this.delegate = delegate;
  }

  public OCacheEntry getDelegate() {
    return delegate;
  }
}
