package com.hypertino.binders.config

import com.typesafe.config.{ConfigValue, ConfigValueType}
import com.hypertino.binders.core.Deserializer
import com.hypertino.binders.value.Value
import com.hypertino.inflector.naming.Converter

import scala.collection.JavaConversions
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.language.experimental.macros

class ConfigDeserializeException(message: String) extends RuntimeException(message)

abstract class ConfigDeserializerBase[C <: Converter, I <: Deserializer[C]]
  (val configValue: Option[ConfigValue], val fieldName: Option[String])
  extends Deserializer[C] {

  def iterator(): Iterator[I] = {
    valueType match {
      case ConfigValueType.OBJECT ⇒ createObjectIterator
      case ConfigValueType.LIST ⇒ createArrayIterator
      case _ ⇒ throw new ConfigDeserializeException(s"Couldn't iterate nonarray/nonobject field: $valueType")
    }
  }

  import scala.collection.JavaConverters._
  protected def createArrayIterator: Iterator[I] = configValue.get.asInstanceOf[java.util.List[ConfigValue]].asScala.map { e ⇒
    createFieldDeserializer(Option(e), None)
  }.toIterator

  protected def createObjectIterator: Iterator[I] = configValue.get.asInstanceOf[java.util.Map[String,ConfigValue]].asScala.map {
    case (name, value) ⇒ createFieldDeserializer(Option(value), Some(name))
  }.toIterator
  
  protected def createFieldDeserializer(fieldValue: Option[ConfigValue], fieldName: Option[String]): I = ??? //new ConfigDeserializer[C] (fieldName, value)

  def isNull: Boolean = valueType == ConfigValueType.NULL
  def readString(): String = configValue.get.unwrapped().toString
  def readInt(): Int = configValue.get.unwrapped() match {
    case i: java.lang.Integer ⇒ i
    case s: java.lang.String ⇒ s.toInt
    case _ ⇒ deserializationFailed("Int")
  }
  def readLong(): Long = configValue.get.unwrapped() match {
    case i: java.lang.Integer ⇒ i.toLong
    case l: java.lang.Long ⇒ l
    case s: java.lang.String ⇒ s.toLong
    case _ ⇒ deserializationFailed("Long")
  }
  def readDouble(): Double = configValue.get.unwrapped() match {
    case i: java.lang.Integer ⇒ i.toDouble
    case l: java.lang.Long ⇒ l.toDouble
    case d: java.lang.Double ⇒ d
    case f: java.lang.Float ⇒ f.toDouble
    case s: java.lang.String ⇒ s.toDouble
    case _ ⇒ deserializationFailed("Double")
  }
  def readFloat(): Float = configValue.get.unwrapped() match {
    case i: java.lang.Integer ⇒ i.toFloat
    case l: java.lang.Long ⇒ l.toFloat
    case d: java.lang.Double ⇒ d.toFloat
    case f: java.lang.Float ⇒ f
    case s: java.lang.String ⇒ s.toFloat
    case _ ⇒ deserializationFailed("Float")
  }
  def readBoolean(): Boolean = configValue.get.unwrapped() match {
    case i: java.lang.Integer ⇒ i != 0
    case l: java.lang.Long ⇒ l != 0
    case b: java.lang.Boolean ⇒ b
    case s: java.lang.String ⇒ s.toBoolean
    case _ ⇒ deserializationFailed("Boolean")
  }
  def readBigDecimal(): BigDecimal = configValue.get.unwrapped() match {
    case i: java.lang.Integer ⇒ BigDecimal(i)
    case l: java.lang.Long ⇒ BigDecimal(l)
    case d: java.lang.Double ⇒ BigDecimal(d)
    case f: java.lang.Float ⇒ BigDecimal(f.toDouble)
    case s: String ⇒ ConfigDeserializer.stringToBigDecimal(s)
    case _ ⇒ deserializationFailed("BigDecimal")
  }

  def readDuration(): Duration = configValue.get.unwrapped() match {
    case i: java.lang.Integer ⇒ Duration(i.toLong, MILLISECONDS)
    case l: java.lang.Long ⇒ Duration(l, MILLISECONDS)
    case d: java.lang.Double ⇒ Duration(d, MILLISECONDS)
    case f: java.lang.Float ⇒ Duration(f.toDouble, MILLISECONDS)
    case s: String ⇒ Duration(s)
    case _ ⇒ deserializationFailed("Duration")
  }

  def readFiniteDuration(): FiniteDuration = {
    readDuration() match {
      case finite: FiniteDuration ⇒ finite
      case other ⇒ deserializationFailed(s"$other is not finite")
    }
  }

  def readValue(): Value = {
    import com.hypertino.binders.value._
    valueType match {
      case ConfigValueType.NUMBER ⇒ Number(readBigDecimal())
      case ConfigValueType.BOOLEAN ⇒ Bool(readBoolean())
      case ConfigValueType.NULL ⇒ Null
      case ConfigValueType.STRING ⇒ Text(readString())
      case ConfigValueType.OBJECT ⇒ {
        var map = new scala.collection.mutable.HashMap[String, Value]()
        iterator().foreach(i => {
          val d = i.asInstanceOf[ConfigDeserializerBase[_,_]]
          map += d.fieldName.get -> d.readValue()
        })
        Obj(map.toMap)
      }
      case ConfigValueType.LIST ⇒ {
        val array = new ArrayBuffer[Value]()
        iterator().foreach(i => array += i.asInstanceOf[ConfigDeserializerBase[_,_]].readValue())
        Lst(array)
      }
      case _ => throw new ConfigDeserializeException(s"Can't deserialize value with type: $valueType")
    }
  }

  protected def valueType: ConfigValueType = configValue match {
    case Some(x) ⇒ x.valueType()
    case None ⇒ ConfigValueType.NULL
  }
  protected def deserializationFailed(expected: String) = throw new ConfigDeserializeException(s"Cant deserialize $valueType as $expected")
}

class ConfigDeserializer[C <: Converter] (fieldValue: Option[ConfigValue], fieldName: Option[String])
  extends ConfigDeserializerBase[C, ConfigDeserializer[C]](fieldValue, fieldName) {
  protected override def createFieldDeserializer(fieldValue: Option[ConfigValue], fieldName: Option[String]): ConfigDeserializer[C] = new ConfigDeserializer[C](fieldValue, fieldName)
}

object ConfigDeserializer {
  private val precision = new java.math.MathContext(150)
  def stringToBigDecimal(s: String): BigDecimal ={
    BigDecimal(s, precision)
  }
}