/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.storage

import java.nio.ByteBuffer

import scala.language.implicitConversions
import scala.language.postfixOps
import scala.language.reflectiveCalls
import scala.reflect.ClassTag

import org.scalatest._

import org.apache.spark._
import org.apache.spark.memory.StaticMemoryManager
import org.apache.spark.serializer.{KryoSerializer, SerializerManager}
import org.apache.spark.storage.memory.{BlockEvictionHandler, MemoryStore, PartiallyUnrolledIterator}
import org.apache.spark.util._
import org.apache.spark.util.io.ChunkedByteBuffer

class MemoryStoreSuite
  extends SparkFunSuite
  with PrivateMethodTester
  with BeforeAndAfterEach
  with ResetSystemProperties {

  var conf: SparkConf = new SparkConf(false)
    .set("spark.test.useCompressedOops", "true")
    .set("spark.storage.unrollFraction", "0.4")
    .set("spark.storage.unrollMemoryThreshold", "512")

  // Reuse a serializer across tests to avoid creating a new thread-local buffer on each test
  val serializer = new KryoSerializer(new SparkConf(false).set("spark.kryoserializer.buffer", "1m"))

  // Implicitly convert strings to BlockIds for test clarity.
  implicit def StringToBlockId(value: String): BlockId = new TestBlockId(value)
  def rdd(rddId: Int, splitId: Int): RDDBlockId = RDDBlockId(rddId, splitId)

  override def beforeEach(): Unit = {
    super.beforeEach()
    // Set the arch to 64-bit and compressedOops to true to get a deterministic test-case
    System.setProperty("os.arch", "amd64")
    val initialize = PrivateMethod[Unit]('initialize)
    SizeEstimator invokePrivate initialize()
  }

  def makeMemoryStore(maxMem: Long): (MemoryStore, BlockInfoManager) = {
    val memManager = new StaticMemoryManager(conf, Long.MaxValue, maxMem, numCores = 1)
    val serializerManager = new SerializerManager(serializer, conf)
    val blockInfoManager = new BlockInfoManager
    val blockEvictionHandler = new BlockEvictionHandler {
      var memoryStore: MemoryStore = _
      override private[storage] def dropFromMemory[T: ClassTag](
          blockId: BlockId,
          data: () => Either[Array[T], ChunkedByteBuffer]): StorageLevel = {
        memoryStore.remove(blockId)
        StorageLevel.NONE
      }
    }
    val memoryStore =
      new MemoryStore(conf, blockInfoManager, serializerManager, memManager, blockEvictionHandler)
    memManager.setMemoryStore(memoryStore)
    blockEvictionHandler.memoryStore = memoryStore
    (memoryStore, blockInfoManager)
  }

  test("reserve/release unroll memory") {
    val (memoryStore, _) = makeMemoryStore(12000)
    assert(memoryStore.currentUnrollMemory === 0)
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)

    def reserveUnrollMemoryForThisTask(memory: Long): Boolean = {
      memoryStore.reserveUnrollMemoryForThisTask(TestBlockId(""), memory)
    }

    // Reserve
    assert(reserveUnrollMemoryForThisTask(100))
    assert(memoryStore.currentUnrollMemoryForThisTask === 100)
    assert(reserveUnrollMemoryForThisTask(200))
    assert(memoryStore.currentUnrollMemoryForThisTask === 300)
    assert(reserveUnrollMemoryForThisTask(500))
    assert(memoryStore.currentUnrollMemoryForThisTask === 800)
    assert(!reserveUnrollMemoryForThisTask(1000000))
    assert(memoryStore.currentUnrollMemoryForThisTask === 800) // not granted
    // Release
    memoryStore.releaseUnrollMemoryForThisTask(100)
    assert(memoryStore.currentUnrollMemoryForThisTask === 700)
    memoryStore.releaseUnrollMemoryForThisTask(100)
    assert(memoryStore.currentUnrollMemoryForThisTask === 600)
    // Reserve again
    assert(reserveUnrollMemoryForThisTask(4400))
    assert(memoryStore.currentUnrollMemoryForThisTask === 5000)
    assert(!reserveUnrollMemoryForThisTask(20000))
    assert(memoryStore.currentUnrollMemoryForThisTask === 5000) // not granted
    // Release again
    memoryStore.releaseUnrollMemoryForThisTask(1000)
    assert(memoryStore.currentUnrollMemoryForThisTask === 4000)
    memoryStore.releaseUnrollMemoryForThisTask() // release all
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)
  }

  test("safely unroll blocks") {
    val smallList = List.fill(40)(new Array[Byte](100))
    val bigList = List.fill(40)(new Array[Byte](1000))
    val ct = implicitly[ClassTag[Array[Byte]]]
    val (memoryStore, blockInfoManager) = makeMemoryStore(12000)
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)

    def putIterator[T](
        blockId: BlockId,
        iter: Iterator[T],
        classTag: ClassTag[T]): Either[PartiallyUnrolledIterator[T], Long] = {
      assert(blockInfoManager.lockNewBlockForWriting(
        blockId,
        new BlockInfo(StorageLevel.MEMORY_ONLY, classTag, tellMaster = false)))
      val res = memoryStore.putIterator(blockId, iter, StorageLevel.MEMORY_ONLY, classTag)
      blockInfoManager.unlock(blockId)
      res
    }

    // Unroll with all the space in the world. This should succeed.
    var putResult = putIterator("unroll", smallList.iterator, ClassTag.Any)
    assert(putResult.isRight)
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)
    smallList.iterator.zip(memoryStore.getValues("unroll").get).foreach { case (e, a) =>
      assert(e === a, "getValues() did not return original values!")
    }
    blockInfoManager.lockForWriting("unroll")
    assert(memoryStore.remove("unroll"))
    blockInfoManager.removeBlock("unroll")

    // Unroll with not enough space. This should succeed after kicking out someBlock1.
    assert(putIterator("someBlock1", smallList.iterator, ct).isRight)
    assert(putIterator("someBlock2", smallList.iterator, ct).isRight)
    putResult = putIterator("unroll", smallList.iterator, ClassTag.Any)
    assert(putResult.isRight)
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)
    assert(memoryStore.contains("someBlock2"))
    assert(!memoryStore.contains("someBlock1"))
    smallList.iterator.zip(memoryStore.getValues("unroll").get).foreach { case (e, a) =>
      assert(e === a, "getValues() did not return original values!")
    }
    blockInfoManager.lockForWriting("unroll")
    assert(memoryStore.remove("unroll"))
    blockInfoManager.removeBlock("unroll")

    // Unroll huge block with not enough space. Even after ensuring free space of 12000 * 0.4 =
    // 4800 bytes, there is still not enough room to unroll this block. This returns an iterator.
    // In the meantime, however, we kicked out someBlock2 before giving up.
    assert(putIterator("someBlock3", smallList.iterator, ct).isRight)
    putResult = putIterator("unroll", bigList.iterator, ClassTag.Any)
    assert(memoryStore.currentUnrollMemoryForThisTask > 0) // we returned an iterator
    assert(!memoryStore.contains("someBlock2"))
    assert(putResult.isLeft)
    bigList.iterator.zip(putResult.left.get).foreach { case (e, a) =>
      assert(e === a, "putIterator() did not return original values!")
    }
    // The unroll memory was freed once the iterator returned by putIterator() was fully traversed.
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)
  }

  test("safely unroll blocks through putIterator") {
    val (memoryStore, blockInfoManager) = makeMemoryStore(12000)
    val smallList = List.fill(40)(new Array[Byte](100))
    val bigList = List.fill(40)(new Array[Byte](1000))
    def smallIterator: Iterator[Any] = smallList.iterator.asInstanceOf[Iterator[Any]]
    def bigIterator: Iterator[Any] = bigList.iterator.asInstanceOf[Iterator[Any]]
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)

    def putIterator[T](
        blockId: BlockId,
        iter: Iterator[T],
        classTag: ClassTag[T]): Either[PartiallyUnrolledIterator[T], Long] = {
      assert(blockInfoManager.lockNewBlockForWriting(
        blockId,
        new BlockInfo(StorageLevel.MEMORY_ONLY, classTag, tellMaster = false)))
      val res = memoryStore.putIterator(blockId, iter, StorageLevel.MEMORY_ONLY, classTag)
      blockInfoManager.unlock(blockId)
      res
    }

    // Unroll with plenty of space. This should succeed and cache both blocks.
    val result1 = putIterator("b1", smallIterator, ClassTag.Any)
    val result2 = putIterator("b2", smallIterator, ClassTag.Any)
    assert(memoryStore.contains("b1"))
    assert(memoryStore.contains("b2"))
    assert(result1.isRight) // unroll was successful
    assert(result2.isRight)
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)

    // Re-put these two blocks so block manager knows about them too. Otherwise, block manager
    // would not know how to drop them from memory later.
    blockInfoManager.lockForWriting("b1")
    memoryStore.remove("b1")
    blockInfoManager.removeBlock("b1")
    blockInfoManager.lockForWriting("b2")
    memoryStore.remove("b2")
    blockInfoManager.removeBlock("b2")
    putIterator("b1", smallIterator, ClassTag.Any)
    putIterator("b2", smallIterator, ClassTag.Any)

    // Unroll with not enough space. This should succeed but kick out b1 in the process.
    val result3 = putIterator("b3", smallIterator, ClassTag.Any)
    assert(result3.isRight)
    assert(!memoryStore.contains("b1"))
    assert(memoryStore.contains("b2"))
    assert(memoryStore.contains("b3"))
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)
    blockInfoManager.lockForWriting("b3")
    assert(memoryStore.remove("b3"))
    blockInfoManager.removeBlock("b3")
    putIterator("b3", smallIterator, ClassTag.Any)

    // Unroll huge block with not enough space. This should fail and kick out b2 in the process.
    val result4 = putIterator("b4", bigIterator, ClassTag.Any)
    assert(result4.isLeft) // unroll was unsuccessful
    assert(!memoryStore.contains("b1"))
    assert(!memoryStore.contains("b2"))
    assert(memoryStore.contains("b3"))
    assert(!memoryStore.contains("b4"))
    assert(memoryStore.currentUnrollMemoryForThisTask > 0) // we returned an iterator
    result4.left.get.close()
    assert(memoryStore.currentUnrollMemoryForThisTask === 0) // close released the unroll memory
  }

  test("multiple unrolls by the same thread") {
    val (memoryStore, _) = makeMemoryStore(12000)
    val smallList = List.fill(40)(new Array[Byte](100))
    def smallIterator: Iterator[Any] = smallList.iterator.asInstanceOf[Iterator[Any]]
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)

    def putIterator(
        blockId: BlockId,
        iter: Iterator[Any]): Either[PartiallyUnrolledIterator[Any], Long] = {
       memoryStore.putIterator(blockId, iter, StorageLevel.MEMORY_ONLY, ClassTag.Any)
    }

    // All unroll memory used is released because putIterator did not return an iterator
    assert(putIterator("b1", smallIterator).isRight)
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)
    assert(putIterator("b2", smallIterator).isRight)
    assert(memoryStore.currentUnrollMemoryForThisTask === 0)

    // Unroll memory is not released because putIterator returned an iterator
    // that still depends on the underlying vector used in the process
    assert(putIterator("b3", smallIterator).isLeft)
    val unrollMemoryAfterB3 = memoryStore.currentUnrollMemoryForThisTask
    assert(unrollMemoryAfterB3 > 0)

    // The unroll memory owned by this thread builds on top of its value after the previous unrolls
    assert(putIterator("b4", smallIterator).isLeft)
    val unrollMemoryAfterB4 = memoryStore.currentUnrollMemoryForThisTask
    assert(unrollMemoryAfterB4 > unrollMemoryAfterB3)

    // ... but only to a certain extent (until we run out of free space to grant new unroll memory)
    assert(putIterator("b5", smallIterator).isLeft)
    val unrollMemoryAfterB5 = memoryStore.currentUnrollMemoryForThisTask
    assert(putIterator("b6", smallIterator).isLeft)
    val unrollMemoryAfterB6 = memoryStore.currentUnrollMemoryForThisTask
    assert(putIterator("b7", smallIterator).isLeft)
    val unrollMemoryAfterB7 = memoryStore.currentUnrollMemoryForThisTask
    assert(unrollMemoryAfterB5 === unrollMemoryAfterB4)
    assert(unrollMemoryAfterB6 === unrollMemoryAfterB4)
    assert(unrollMemoryAfterB7 === unrollMemoryAfterB4)
  }

  test("lazily create a big ByteBuffer to avoid OOM if it cannot be put into MemoryStore") {
    val (memoryStore, blockInfoManager) = makeMemoryStore(12000)
    val blockId = BlockId("rdd_3_10")
    blockInfoManager.lockNewBlockForWriting(
      blockId, new BlockInfo(StorageLevel.MEMORY_ONLY, ClassTag.Any, tellMaster = false))
    memoryStore.putBytes(blockId, 13000, () => {
      fail("A big ByteBuffer that cannot be put into MemoryStore should not be created")
    })
  }

  test("put a small ByteBuffer to MemoryStore") {
    val (memoryStore, _) = makeMemoryStore(12000)
    val blockId = BlockId("rdd_3_10")
    var bytes: ChunkedByteBuffer = null
    memoryStore.putBytes(blockId, 10000, () => {
      bytes = new ChunkedByteBuffer(ByteBuffer.allocate(10000))
      bytes
    })
    assert(memoryStore.getSize(blockId) === 10000)
  }
}
