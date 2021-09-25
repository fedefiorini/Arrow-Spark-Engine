package org.apache.spark.rdd

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.types.Types.MinorType
import org.apache.arrow.vector.{BigIntVector, IntVector, StringVector, ValueVector, VarBinaryVector, ZeroVector}
import org.apache.spark.{Partition, SparkException}
import org.apache.spark.internal.Logging

import java.io.{Externalizable, ObjectInput, ObjectOutput}
import scala.reflect.runtime.universe._

/**
 * ArrowPartition offers the main logic for treating the Arrow vectors in Spark.
 * It derives from the ParallelCollectionPartition in terms of usage, but instead of having
 * Seq[T] as data injected it has an Arrow-backed ValueVector of any type.
 *
 * The individual values in the vector are then retrieved when calling the iterator[T], where
 * T is inferred from the type of the vector used as input.
 *
 * See ArrowCompositePartition for multi-vector case
 *
 * Since the value vectors cannot be serialized, it requires using Externalizable to "trick" Spark
 * into thinking it's actually serializing the vectors, whereas it's actually writing them as
 * Array[Byte], therefore maintaining the correct native representation
 */
class ArrowPartition extends Partition with Externalizable with Logging {

  private var _rddId : Long = 0L
  private var _slice : Int = 0
  @transient private var _data : ValueVector = new ZeroVector

  def this(rddId : Long, slice : Int, data : ValueVector) = {
    this
    _rddId = rddId
    _slice = slice
    _data = data
  }

  /* Make vector accessible for sub-classes (when ShuffledArrowPartition
  * gets proper implementation, if required. If not, leave it for future */
  def getVector: ValueVector = {
    _data
  }
  /**
   * This iterator actually iterates over the vector and retrieves each value
   * It's called by the compute(...) method in the ArrowRDD and it generalizes
   * the type based on the vector it iterates over to.
   *
   * @tparam T the actual data type for the elements of the given vector
   * @return the Iterator[T] over the vector values
   */
  def iterator[T: TypeTag] : Iterator[T] = {
    var idx = 0

    new Iterator[T] {
      override def hasNext: Boolean = idx < _data.getValueCount

      override def next(): T = {
        val value = _data.getObject(idx).asInstanceOf[T]
        idx += 1

        value
      }
    }
  }

  /* hashCode(), equals() and index are copied from ParallelCollectionPartition, since
  * the logic doesn't change for this example. Only difference, in hashCode(), "43"
  * is used instead of "41" */
  override def hashCode() : Int = (43 * (43 + _rddId) + _slice).toInt

  override def equals(other: Any) : Boolean = other match {
    case that: ArrowPartition => this._rddId == that._rddId && this._slice == that._slice
    case _ => false
  }

  override def index: Int = _slice

  /*METHODS INHERITED FROM EXTERNALIZABLE AND IMPLEMENTED
  *
  * When writing and reading the ArrowPartition it's required to use both the vector's length and
  * the vector's actual Type, in order to create the correct vector and populate it with the right
  * data upon reading from the byte stream used during the de-/serialization process */
  override def writeExternal(out: ObjectOutput): Unit = {
    out.writeLong(_rddId)
    out.writeInt(_slice)

    val len = _data.getValueCount
    out.writeInt(len)
    val vecType = _data.getMinorType
    out.writeObject(vecType)
    for (i <- 0 until len) out.writeObject(_data.getObject(i))
  }

  override def readExternal(in: ObjectInput): Unit = {
    _rddId = in.readLong()
    _slice = in.readInt()

    val len = in.readInt()
    val vecType = in.readObject().asInstanceOf[MinorType]
    val allocator = new RootAllocator(Long.MaxValue)

    /* Depending on the type, the correct vector is instantiated
    *
    * So far, only BIGINT (Long) and VARBINARY (String) vectors have been implemented here,
    * which should define the basic working conditions for each primitive data type.
    * In particular, BIGINT shows this solution works for any fixed-width primitive type, whereas
    * VARBINARY does the same for variable-width primitive types */
    vecType match {
      case MinorType.INT =>
        _data = new IntVector("vector", allocator)
        _data.asInstanceOf[IntVector].allocateNew(len)
        for (i <- 0 until len) _data.asInstanceOf[IntVector]
          .setSafe(i, in.readObject().asInstanceOf[Int])
        _data.setValueCount(len)
      case MinorType.BIGINT =>
        _data = new BigIntVector("vector", allocator)
        _data.asInstanceOf[BigIntVector].allocateNew(len)
        for (i <- 0 until len) _data.asInstanceOf[BigIntVector]
          .setSafe(i, in.readObject().asInstanceOf[Long])
        _data.setValueCount(len)
      case MinorType.VARBINARY =>
        _data = new VarBinaryVector("vector", allocator)
        _data.asInstanceOf[VarBinaryVector].allocateNew(len)
        for (i <- 0 until len) _data.asInstanceOf[VarBinaryVector]
          .setSafe(i, in.readObject().asInstanceOf[Array[Byte]])
        _data.setValueCount(len)
      case MinorType.STRING =>
        _data = new StringVector("vector", allocator)
        _data.asInstanceOf[StringVector].allocateNew(len)
        for (i <- 0 until len) _data.asInstanceOf[StringVector]
          .setSafe(i, in.readObject().asInstanceOf[String])
        _data.setValueCount(len)
      case _ => throw new SparkException("Unsupported Arrow Vector")
    }
  }
}