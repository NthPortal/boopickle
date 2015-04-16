package boopickle

import java.nio.ByteBuffer
import java.util.UUID

import boopickle.Unpickler._

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.language.experimental.macros
import scala.language.higherKinds
import scala.reflect.ClassTag

object Unpickle {
  def apply[A](implicit u: Unpickler[A]) = UnpickledCurry(u)
}

case class UnpickledCurry[A](u: Unpickler[A]) {
  def apply(implicit state: UnpickleState): A = u.unpickle(state)

  def fromBytes(bytes: ByteBuffer): A = u.unpickle(new UnpickleState(new Decoder(bytes)))

  def fromState(state: UnpickleState): A = u.unpickle(state)
}

trait Unpickler[A] {
  def unpickle(implicit state: UnpickleState): A
}

trait UnpicklerHelper {
  type U[A] = Unpickler[A]
  /**
   * Helper function to unpickle a type
   */
  def read[A](implicit state: UnpickleState, u: U[A]): A = u.unpickle
}

object Unpickler extends TupleUnpicklers with MaterializeUnpicklerFallback {

  import Constants._

  implicit object UnitUnpickler extends U[Unit] {
    @inline override def unpickle(implicit state: UnpickleState): Unit = { /* do nothing */ }
  }

  implicit object BooleanUnpickler extends U[Boolean] {
    @inline override def unpickle(implicit state: UnpickleState): Boolean = {
      if (state.dec.readByte == 1)
        true
      else
        false
    }
  }

  implicit object ByteUnpickler extends U[Byte] {
    @inline override def unpickle(implicit state: UnpickleState): Byte = state.dec.readByte
  }

  implicit object ShortUnpickler extends U[Short] {
    @inline override def unpickle(implicit state: UnpickleState): Short = state.dec.readInt.toShort
  }

  implicit object CharUnpickler extends U[Char] {
    @inline override def unpickle(implicit state: UnpickleState): Char = state.dec.readChar
  }

  implicit object IntUnpickler extends U[Int] {
    @inline override def unpickle(implicit state: UnpickleState): Int = state.dec.readInt
  }

  implicit object LongUnpickler extends U[Long] {
    @inline override def unpickle(implicit state: UnpickleState): Long = state.dec.readLong
  }

  implicit object FloatUnpickler extends U[Float] {
    @inline override def unpickle(implicit state: UnpickleState): Float = state.dec.readFloat
  }

  implicit object DoubleUnpickler extends U[Double] {
    @inline override def unpickle(implicit state: UnpickleState): Double = state.dec.readDouble
  }

  implicit object DurationUnpickler extends U[Duration] {
    @inline override def unpickle(implicit state: UnpickleState): Duration = {
      state.dec.readLongCode match {
        case Left(c) if c == specialCode(DurationInf) =>
          Duration.Inf
        case Left(c) if c == specialCode(DurationMinusInf) =>
          Duration.MinusInf
        case Left(c) if c == specialCode(DurationUndefined) =>
          Duration.Undefined
        case Right(value) =>
          Duration.fromNanos(value)
      }
    }
  }

  implicit object StringUnpickler extends U[String] {
    override def unpickle(implicit state: UnpickleState): String = {
      state.dec.readIntCode match {
        case Left(code) =>
          throw new IllegalArgumentException("Unknown string length coding")
        case Right(0) => ""
        case Right(idx) if idx < 0 =>
          state.immutableFor[String](-idx)
        case Right(len) =>
          val s = state.dec.readString(len)
          // add short strings to immutable refs
          if (len < MaxRefStringLen)
            state.addImmutableRef(s)
          s
      }
    }
  }

  implicit object UUIDUnpickler extends U[UUID] {
    @inline override def unpickle(implicit state: UnpickleState): UUID = {
      new UUID(state.dec.readRawLong, state.dec.readRawLong)
    }
  }

  implicit def OptionUnpickler[T: U]: U[Option[T]] = new U[Option[T]] {
    override def unpickle(implicit state: UnpickleState): Option[T] = {
      state.dec.readIntCode match {
        case Right(OptionSome) =>
          val o = Some(read[T])
          state.addIdentityRef(o)
          o
        case Right(idx) if idx < 0 =>
          state.identityFor[Option[T]](-idx)
        case _ =>
          throw new IllegalArgumentException("Invalid coding for Option type")
      }
    }
  }

  implicit def EitherUnpickler[T: U, S: U]: U[Either[T, S]] = new U[Either[T, S]] {
    override def unpickle(implicit state: UnpickleState): Either[T, S] = {
      state.dec.readIntCode match {
        case Right(EitherLeft) =>
          Left(read[T])
        case Right(EitherRight) =>
          Right(read[S])
        case Right(idx) if idx < 0 =>
          state.identityFor[Either[T, S]](-idx)
        case _ =>
          throw new IllegalArgumentException("Invalid coding for Either type")
      }
    }
  }

  import collection.generic.CanBuildFrom

  /**
   * U for all iterables that have a builder. Using a builder is an efficient way to build the correct collection right away.
   *
   * @tparam T Type of the values
   * @tparam V Type of the iterable
   * @return
   */
  implicit def SeqishUnpickler[T: U, V[_] <: Iterable[_]]
  (implicit cbf: CanBuildFrom[Nothing, T, V[T]]): U[V[T]] = new U[V[T]] {
    override def unpickle(implicit state: UnpickleState): V[T] = {
      state.dec.readIntCode match {
        case Left(code) =>
          throw new IllegalArgumentException("Unknown sequence length coding")
        case Right(0) =>
          // empty sequence
          cbf().result()
        case Right(idx) if idx < 0 =>
          state.identityFor[V[T]](-idx)
        case Right(len) =>
          val b = cbf()
          for (i <- 0 until len) {
            b += read[T]
          }
          val res = b.result()
          state.addIdentityRef(res)
          res
      }
    }
  }

  /**
   * U for Arrays
   *
   * @tparam T Type of the values
   * @return
   */
  implicit def ArrayUnpickler[T: U : ClassTag]: U[Array[T]] = new U[Array[T]] {
    override def unpickle(implicit state: UnpickleState): Array[T] = {
      state.dec.readIntCode match {
        case Left(code) =>
          throw new IllegalArgumentException("Unknown sequence length coding")
        case Right(0) =>
          // empty Array
          Array.empty[T]
        case Right(idx) if idx < 0 =>
          state.identityFor[Array[T]](-idx)
        case Right(len) =>
          val a = new Array[T](len)
          for (i <- 0 until len) {
            a(i) = read[T]
          }
          state.addIdentityRef(a)
          a
      }
    }
  }

  /**
   * U for all Map types that have a builder. Using a builder is an efficient way to build the correct map right away.
   *
   * @tparam T Type of the values
   * @tparam V Type of the map
   * @return
   */
  implicit def MapUnpickler[T: U, S: U, V[_, _] <: scala.collection.Map[T, S]]
  (implicit cbf: CanBuildFrom[Nothing, (T, S), V[T, S]]): U[V[T, S]] = new U[V[T, S]] {
    override def unpickle(implicit state: UnpickleState): V[T, S] = {
      state.dec.readIntCode match {
        case Left(code) =>
          throw new IllegalArgumentException("Unknown sequence length coding")
        case Right(0) =>
          // empty map
          cbf().result()
        case Right(idx) if idx < 0 =>
          state.identityFor[V[T, S]](-idx)
        case Right(len) =>
          val b = cbf()
          for (i <- 0 until len) {
            b += read[T] -> read[S]
          }
          val res = b.result()
          state.addIdentityRef(res)
          res
      }
    }
  }

  implicit def toUnpickler[A <: AnyRef](implicit pair: PicklerPair[A]): Unpickler[A] = pair.unpickler
}

final class UnpickleState(val dec: Decoder) {
  /**
   * Object reference for pickled immutable objects
   *
   * Index 0 is not used
   * Index 1 = null
   * Index 2-n, references to pickled immutable objects
   */
  private[this] val immutableRefs = new mutable.ArrayBuffer[AnyRef](16)

  // initialize with basic data
  addImmutableRef(null)
  addImmutableRef(null)
  Constants.immutableInitData.foreach(addImmutableRef)

  private[boopickle] def immutableFor[A <: AnyRef](ref: Int): A = {
    assert(ref > 0)
    immutableRefs(ref).asInstanceOf[A]
  }

  private[boopickle] def addImmutableRef(obj: AnyRef): Unit = {
    immutableRefs += obj
  }

  /**
   * Object reference for pickled mutable objects (use identity for equality comparison)
   *
   * Index 0 is not used
   * Index 1 = null
   * Index 2-n, references to pickled objects
   */
  private[this] val identityRefs = new mutable.ArrayBuffer[AnyRef](16)

  // initialize with basic data
  addIdentityRef(null)
  addIdentityRef(null)
  Constants.identityInitData.foreach(addIdentityRef)

  private[boopickle] def identityFor[A <: AnyRef](ref: Int): A = {
    assert(ref > 0)
    identityRefs(ref).asInstanceOf[A]
  }

  private[boopickle] def addIdentityRef(obj: AnyRef): Unit = {
    identityRefs += obj
  }

  def unpickle[A](implicit state: UnpickleState, u: U[A]): A = u.unpickle
}

object UnpickleState {
  def apply(bytes: ByteBuffer) = new UnpickleState(new Decoder(bytes))
}

trait MaterializeUnpicklerFallback {
  implicit def materializeUnpickler[T]: Unpickler[T] = macro PicklerMaterializersImpl.materializeUnpickler[T]
}