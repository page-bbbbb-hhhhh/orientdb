package com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.cellbtree.singlevalue.v1.cellbtreebucketsinglevalue;

import com.orientechnologies.common.serialization.types.OIntegerSerializer;
import com.orientechnologies.orient.core.storage.cache.OCacheEntry;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.orientechnologies.orient.core.storage.impl.local.paginated.wal.po.PageOperationRecord;
import com.orientechnologies.orient.core.storage.index.sbtree.singlevalue.v1.OCellBTreeBucketSingleValue;

import java.nio.ByteBuffer;

public final class CellBTreeBucketSingleValueAddLeafEntryPO extends PageOperationRecord {
  private int    index;
  private byte[] key;
  private byte[] value;

  public CellBTreeBucketSingleValueAddLeafEntryPO() {
  }

  public CellBTreeBucketSingleValueAddLeafEntryPO(int index, byte[] key, byte[] value) {
    this.index = index;
    this.key = key;
    this.value = value;
  }

  public int getIndex() {
    return index;
  }

  public byte[] getKey() {
    return key;
  }

  public byte[] getValue() {
    return value;
  }

  @Override
  public void redo(OCacheEntry cacheEntry) {
    final OCellBTreeBucketSingleValue bucket = new OCellBTreeBucketSingleValue(cacheEntry);
    final boolean result = bucket.addLeafEntry(index, key, value);
    if (!result) {
      throw new IllegalStateException("Can not redo leaf entry addition");
    }
  }

  @Override
  public void undo(OCacheEntry cacheEntry) {
    final OCellBTreeBucketSingleValue bucket = new OCellBTreeBucketSingleValue(cacheEntry);
    //noinspection unchecked
    bucket.remove(index, key.length, null, null);
  }

  @Override
  public byte getId() {
    return WALRecordTypes.CELL_BTREE_BUCKET_SINGLE_VALUE_V1_ADD_LEAD_ENTRY_PO;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + 3 * OIntegerSerializer.INT_SIZE + key.length + value.length;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);

    buffer.putInt(index);

    buffer.putInt(key.length);
    buffer.put(key);

    buffer.putInt(value.length);
    buffer.put(value);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);

    index = buffer.getInt();

    final int keyLen = buffer.getInt();
    key = new byte[keyLen];
    buffer.get(key);

    final int valueLen = buffer.getInt();
    value = new byte[valueLen];
    buffer.get(value);
  }
}
