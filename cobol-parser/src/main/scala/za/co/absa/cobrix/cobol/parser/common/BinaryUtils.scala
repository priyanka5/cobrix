/*
 * Copyright 2018 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.cobrix.cobol.parser.common

import java.nio.ByteBuffer

import org.slf4j.LoggerFactory
import scodec._
import scodec.bits.BitVector
import za.co.absa.cobrix.cobol.parser.encoding.{ASCII, EBCDIC, Encoding}
import za.co.absa.cobrix.cobol.parser.position

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

/** Utilites for decoding Cobol binary data files **/
//noinspection RedundantBlock
object BinaryUtils {
  private val logger = LoggerFactory.getLogger(this.getClass)

  lazy val floatB: Codec[Float] = scodec.codecs.float
  lazy val floatL: Codec[Float] = scodec.codecs.floatL
  lazy val doubleB: Codec[Double] = scodec.codecs.double
  lazy val doubleL: Codec[Double] = scodec.codecs.doubleL

  /** This is the EBCDIC to ASCII conversion table **/
  lazy val ebcdic2ascii: Array[Char] = {
    val clf = '\r'
    val ccr = '\n'
    val spc = ' '
    val qts = '\''
    val qtd = '\"'
    val bsh = '\\'
    Array[Char](
      spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, ccr, spc, spc, //   0 -  15
      spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, //  16 -  31
      spc, spc, spc, spc, spc, clf, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, //  32 -  47
      spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, spc, //  48 -  63
      ' ', ' ', spc, spc, spc, spc, spc, spc, spc, spc, '¢', '.', '<', '(', '+', '|', //  64 -  79
      '&', spc, spc, spc, spc, spc, spc, spc, spc, spc, '!', '$', '*', ')', ';', '¬', //  80 -  95
      '-', '/', spc, spc, spc, spc, spc, spc, spc, spc, '¦', ',', '%', '_', '>', '?', //  96 - 111
      spc, spc, spc, spc, spc, spc, spc, spc, spc, '`', ':', '#', '@', qts, '=', qtd, // 112 - 127
      spc, 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', spc, spc, spc, spc, spc, '±', // 128 - 143
      spc, 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', spc, spc, spc, spc, spc, spc, // 144 - 159
      spc, '~', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', spc, spc, spc, spc, spc, spc, // 160 - 175
      '^', spc, spc, spc, spc, spc, spc, spc, spc, spc, '[', ']', spc, spc, spc, spc, // 176 - 191
      '{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', '-', spc, spc, spc, spc, spc, // 192 - 207
      '}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', spc, spc, spc, spc, spc, spc, // 208 - 223
      bsh, spc, 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', spc, spc, spc, spc, spc, spc, // 224 - 239
      '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', spc, spc, spc, spc, spc, spc) // 240 - 255
  }

  lazy val ascii2ebcdic: Array[Byte] = Array[Byte] (
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  //   0 -   7
    0x40.toByte, 0x40.toByte, 0x0d.toByte, 0x40.toByte, 0x40.toByte, 0x25.toByte, 0x40.toByte, 0x40.toByte,  //   8 -  15
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  //  16 -  23
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  //  24 -  31
    0x40.toByte, 0x5a.toByte, 0x7f.toByte, 0x7b.toByte, 0x5b.toByte, 0x6c.toByte, 0x50.toByte, 0x7d.toByte,  //  32 -  39
    0x4d.toByte, 0x5d.toByte, 0x5c.toByte, 0x4e.toByte, 0x6b.toByte, 0x60.toByte, 0x4b.toByte, 0x61.toByte,  //  40 -  47
    0xf0.toByte, 0xf1.toByte, 0xf2.toByte, 0xf3.toByte, 0xf4.toByte, 0xf5.toByte, 0xf6.toByte, 0xf7.toByte,  //  48 -  55
    0xf8.toByte, 0xf9.toByte, 0x7a.toByte, 0x5e.toByte, 0x4c.toByte, 0x7e.toByte, 0x6e.toByte, 0x6f.toByte,  //  56 -  63
    0x7c.toByte, 0xc1.toByte, 0xc2.toByte, 0xc3.toByte, 0xc4.toByte, 0xc5.toByte, 0xc6.toByte, 0xc7.toByte,  //  64 -  71
    0xc8.toByte, 0xc9.toByte, 0xd1.toByte, 0xd2.toByte, 0xd3.toByte, 0xd4.toByte, 0xd5.toByte, 0xd6.toByte,  //  72 -  79
    0xd7.toByte, 0xd8.toByte, 0xd9.toByte, 0xe2.toByte, 0xe3.toByte, 0xe4.toByte, 0xe5.toByte, 0xe6.toByte,  //  80 -  87
    0xe7.toByte, 0xe8.toByte, 0xe9.toByte, 0xba.toByte, 0xe0.toByte, 0xbb.toByte, 0xb0.toByte, 0x6d.toByte,  //  88 -  95
    0x79.toByte, 0x81.toByte, 0x82.toByte, 0x83.toByte, 0x84.toByte, 0x85.toByte, 0x86.toByte, 0x87.toByte,  //  96 - 103
    0x88.toByte, 0x89.toByte, 0x91.toByte, 0x92.toByte, 0x93.toByte, 0x94.toByte, 0x95.toByte, 0x96.toByte,  // 104 - 111
    0x97.toByte, 0x98.toByte, 0x99.toByte, 0xa2.toByte, 0xa3.toByte, 0xa4.toByte, 0xa5.toByte, 0xa6.toByte,  // 112 - 119
    0xa7.toByte, 0xa8.toByte, 0xa9.toByte, 0xc0.toByte, 0x4f.toByte, 0xd0.toByte, 0xa1.toByte, 0x40.toByte,  // 120 - 127
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 128 - 135
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 136 - 143
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 144 - 151
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 152 - 159
    0x40.toByte, 0x40.toByte, 0x4a.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x6a.toByte, 0x40.toByte,  // 160 - 167
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x5f.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 168 - 175
    0x40.toByte, 0x8f.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 176 - 183
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 184 - 191
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 192 - 199
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 200 - 207
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 208 - 215
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 216 - 223
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 224 - 231
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 232 - 239
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte,  // 240 - 247
    0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte, 0x40.toByte   // 248 - 255
  )

  /** Convert an EBCDIC character to ASCII */
  def ebcdicToAscii(byte: Byte): Char = ebcdic2ascii(byte)

  /** Convert an ASCII character to EBCDIC */
  def asciiToEbcdic(char: Char): Byte = ascii2ebcdic(char.toByte)

  def wordAlign(f: BitVector, wordSize: Int, align: position.Position): BitVector = {
    require(f.size <= wordSize)
    align match {
      case position.Left if f.size != wordSize => f.padLeft(wordSize - f.size)
      case position.Right if f.size != wordSize => f.padRight(wordSize - f.size)
      case _ => f
    }
  }

  /** Get the bit count of a cobol data type
    *
    * @param codec     EBCDIC / ASCII
    * @param comp      A type of compact stirage
    * @param precision The precision (the number of digits) of the type
    * @return
    */
  def getBitCount(codec: Codec[_ <: AnyVal], comp: Option[Int], precision: Int): Int = {
    comp match {
      case Some(value) =>
        value match {
          case compact if compact == 3 =>
            (precision + 1) * codec.sizeBound.lowerBound.toInt //bcd
          case _ => codec.sizeBound.lowerBound.toInt // bin/float/floatL
        }
      case None => precision * codec.sizeBound.lowerBound.toInt
    }
  }

  def getBytesCount(compression: Option[Int], precision: Int, isSigned: Boolean, isSignSeparate: Boolean): Int = {
    import Constants._
    val isRealSigned = if (isSignSeparate) false else isSigned
    val bytes = compression match {
      case Some(comp) if comp == compBinary1 || comp == compBinary1 || comp == compBinary2 =>
        // if native binary follow IBM guide to digit binary length
        precision match {
          case p if p >= 1 && p <= 2 && comp == compBinaryCompilerSpecific => 1 // byte
          case p if p >= minShortPrecision && p <= maxShortPrecision => binaryShortSizeBytes
          case p if p >= minIntegerPrecision && p <= maxIntegerPrecision => binaryIntSizeBytes
          case p if p >= minLongPrecision && p <= maxLongPrecision => binaryLongSizeBytes
          case p => // bigint
            val signBit = if (isRealSigned) 1 else 0
            val numberOfBytes = ((Math.log(10)/ Math.log(2))*precision + signBit)/8
            math.ceil(numberOfBytes).toInt
        }
      case Some(comp) if comp == compFloat => floatSize
      case Some(comp) if comp == compDouble => doubleSize
      case Some(comp) if comp == compBCD =>   // bcd
        if (precision % 2 == 0)
          precision / 2 + 1
        else
          precision / 2
      case Some(comp) => throw new IllegalArgumentException(s"Illegal clause COMP-$comp.")
      case None => precision
    }
    if (isSignSeparate) bytes + 1 else bytes
  }

  /** Decode the bits that are located in a binary file to actual human readable information
    *
    * @param codec        scodec codec
    * @param enc          encoding type
    * @param scale        size of data stucture
    * @param bits         bits that need to be decoded
    * @param comp         compaction of the bits
    * @param align        bits alignment
    * @param signPosition sign position of a signed data type
    * @return
    */
  def decode(codec: Codec[_ <: AnyVal], enc: Encoding, scale: Int, bits: BitVector, comp: Option[Int], align: Option[position.Position] = None,
             signPosition: Option[position.Position]): Array[Byte] = {
    val digitBitSize = codec.sizeBound.lowerBound.toInt
    val bytes = enc match {
      case _: ASCII => comp match {
        case Some(compact) => compact match {
          case a if a == 3 => { //bcd
            val bte = for (x <- 0 until scale) yield {
              val bts = wordAlign(bits.slice(x * digitBitSize, (x * digitBitSize) + digitBitSize), digitBitSize, align.getOrElse(position.Left))
              Codec.decode(bts)(codec).require.value.asInstanceOf[Double].toByte
            }
            bte.toArray
          }
          case _ => { //bin
            //            val bts = wordAlign(bits, digitBitSize, align.getOrElse(Left))
            val bte = Codec.decode(bits)(codec).require.value.asInstanceOf[Double].toByte
            (bte :: Nil).toArray
          }
        }
        case None => { // display i.e. no comp
          val bte = for (x <- 0 until scale) yield {
            val bts = wordAlign(bits.slice(x * digitBitSize, (x * digitBitSize) + digitBitSize), digitBitSize, align.getOrElse(position.Left))
            Codec.decode(bts)(codec).require.value.asInstanceOf[Double].toByte
          }
          bte.toArray
        }
      }
      case _: EBCDIC => comp match {
        case Some(compact) => compact match {
          case a if a == 3 => { //bcd
            logger.debug("BCD, bits : " + bits)
            val bte = for (x <- 0 to scale) yield {
              val bts = wordAlign(bits.slice(x * digitBitSize, (x * digitBitSize) + digitBitSize), digitBitSize, align.getOrElse(position.Left))
              logger.debug("bts : " + bts.toBin)
              logger.debug("codec : " + codec)
              logger.debug("value : " + Codec.decode(bts)(codec).require.value.asInstanceOf[Number].doubleValue())
              Codec.decode(bts)(codec).require.value.asInstanceOf[Number].doubleValue().toByte
            }
            bte.toArray
          }
          case _ => { //bin
            //            val bts = wordAlign(bits, digitBitSize, align.getOrElse(Left))
            logger.debug("bts : " + bits.toBin)
            logger.debug("codec : " + codec)
            val buf = ByteBuffer.allocate(8)
            logger.debug("codec : " + codec.toString)
            val decValue = Codec.decode(bits)(codec).require.value.asInstanceOf[Number].doubleValue()
            logger.debug("decValue : " + decValue)
            val byteArr = buf.putDouble(decValue).array()
            logger.debug("byteArr : " + byteArr)
            byteArr
          }
        }
        case None => { // display i.e. no comp
          val bte = for (x <- 0 until scale) yield {
            val bts = wordAlign(bits.slice(x * digitBitSize, (x * digitBitSize) + digitBitSize), digitBitSize, align.getOrElse(position.Left))
            logger.debug("bts : " + bts.toBin)
            Codec.decode(bts)(codec).require.value.asInstanceOf[Number].doubleValue().toByte
          }
          bte.toArray
        }
      }
    }
    bytes
  }

  /** decode an array of bytes to actual characters that represent their binary counterparts.
    *
    * @param byteArr byte array that represents the binary data
    * @param enc     encoding type
    * @param comp    binary compaction type
    * @return a string representation of the binary data
    */
  def charDecode(byteArr: Array[Byte], enc: Option[Encoding], comp: Option[Int]): String = {
    val ans = enc match {
      case Some(ASCII()) => byteArr.map(byte => {
        byte.toInt
      })
      case Some(EBCDIC()) =>
        val finalStringVal = comp match {
          case Some(compact) => {
            val compValue = compact match {
              case a if a == 3 => { //bcd
                val digitString = for {
                  idx <- byteArr.indices
                } yield {
                  if (idx == byteArr.length - 1) { //last byte is sign
                    byteArr(idx) match {
                      case 0x0C => " " // was +
                      case 0x0D => "-"
                      case 0x0F => " " // was +, unsigned
                      case _ =>
                        // Todo Remove this
                        println(s"Unknown singature nybble encountered! ${byteArr(idx).toString}")
                        byteArr(idx).toString // No sign
                    }
                  }
                  else {
                    byteArr(idx).toString
                  }
                }
                logger.debug("digitString : " + digitString)
                s"${digitString.last}${digitString.head}${digitString.tail.dropRight(1).mkString("")}"
              }
              case _ => { //bin
                val buf = ByteBuffer.wrap(byteArr)
                // Todo Why Double??? Revis the logic
                buf.getDouble.toString //returns number value as a string "1500"
              }
            }
            compValue
          }
          case None => {
            val digitString = for {
              idx <- byteArr.indices
            } yield {
              val unsignedByte = (256 + byteArr(idx).toInt) % 256
              ebcdic2ascii(unsignedByte)
            }
            digitString.mkString("")
          }
        }
        finalStringVal

      case _ => throw new Exception("No character set was defined for decoding")
    }
    ans.toString
  }

  /** A decoder for any string fields (alphabetical or any char)
    *
    * @param bytes A byte array that represents the binary data
    * @return A string representation of the binary data
    */
  def decodeString(enc: Encoding, bytes: Array[Byte], length: Int): String = {
    val str = enc match {
      case _: EBCDIC => {
        var i = 0
        val buf = new StringBuffer(length)
        while (i < bytes.length && i < length) {
          buf.append(ebcdic2ascii((bytes(i) + 256) % 256))
          i = i + 1
        }
        buf.toString
      }
      case _ => {
        var i = 0
        val buf = new StringBuffer(length)
        while (i < bytes.length && i < length) {
          buf.append(bytes(i).toChar)
          i = i + 1
        }
        buf.toString
      }
    }
    str.trim
  }

  /** A decoder for various numeric formats
    *
    * @param bytes A byte array that represents the binary data
    * @return A string representation of the binary data
    */
  def decodeCobolNumber(enc: Encoding, bytes: Array[Byte], compact: Option[Int], precision: Int, scale: Int, explicitDecimal: Boolean, signed: Boolean, isSignSeparate: Boolean): Option[String] = {
    compact match {
      case None =>
        // DISPLAY format
        decodeUncompressedNumber(enc, bytes, explicitDecimal, scale, isSignSeparate)
      case Some(1) =>
        // COMP-1 aka 32-bit floating point number
        Some(decodeFloatingPointNumber(bytes, bigEndian = true))
      case Some(2) =>
        // COMP-2 aka 64-bit floating point number
        Some(decodeFloatingPointNumber(bytes, bigEndian = true))
      case Some(3) =>
        // COMP-3 aka BCD-encoded number
        decodeSignedBCD(bytes, scale)
      case Some(4) =>
        // COMP aka BINARY encoded number
        Some(decodeBinaryNumber(bytes, bigEndian = true, signed = signed, scale))
      case Some(5) =>
        // COMP aka BINARY encoded number
        Some(decodeBinaryNumber(bytes, bigEndian = false, signed = signed, scale))
      case _ =>
        throw new IllegalStateException(s"Unknown compression format ($compact).")
    }
  }
  /** A decoder for uncompressed (aka DISPLAY) binary numbers
    *
    * @param bytes A byte array that represents the binary data
    * @return A string representation of the binary data
    */
  def decodeUncompressedNumber(enc: Encoding, bytes: Array[Byte], explicitDecimal: Boolean, scale: Int, isSignSeparate: Boolean): Option[String] = {
    // ToDo Add leading signal support
    val chars = new StringBuffer(bytes.length + 1)
    val decimalPointPosition = bytes.length - scale
    var i = 0
    while (i < bytes.length) {
      if (i == decimalPointPosition && !explicitDecimal) {
        chars.append('.')
      }
      enc match {
        case _: EBCDIC => chars.append(ebcdic2ascii((bytes(i) + 256) % 256))
        case _ => chars.append(bytes(i).toChar)
      }

      i += 1
    }
    validateAndFormatNumber(chars.toString.trim)
  }

  /** Decode a binary encoded decimal (BCD) aka COMP-3 format to a String
    *
    * @param bytes A byte array that represents the binary data
    * @param scale A decimal scale if a number is a decimal. Should be greater or equal to zero
    * @return Some(str) - a string representation of the binary data, None if the data is not properly formatted
    */
  def decodeSignedBCD(bytes: Array[Byte], scale: Int = 0): Option[String] = {
    if (scale < 0) {
      throw new IllegalArgumentException(s"Invalid scale=$scale, should be greater or equal to zero.")
    }
    if (bytes.length < 1) {
      return Some("0")
    }
    var i: Int = 0
    var sign = ""
    val chars = new StringBuffer(bytes.length * 2 + 2)
    val decimalPointPosition = bytes.length*2 - (scale + 1)

    while (i < bytes.length) {
      val b = bytes(i)
      val lowNibble = b & 0x0f
      val highNibble = (b >> 4) & 0x0f
      if (highNibble >= 0 && highNibble < 10) {
        chars.append(('0'.toByte + highNibble).toChar)
      }
      else {
        // invalid nibble encountered - the format is wrong
        return None
      }

      if (i + 1 == bytes.length) {
        // The last nibble is a sign
        sign = lowNibble match {
          case 0x0C => "" // +, signed
          case 0x0D => "-"
          case 0x0F => "" // +, unsigned
          case _ =>
            // invalid nibble encountered - the format is wrong
            return None
        }
      }
      else {
        if (lowNibble >= 0 && lowNibble < 10) {
          chars.append(('0'.toByte + lowNibble).toChar)
        }
        else {
          // invalid nibble encountered - the format is wrong
         return None
        }
      }
      i = i + 1
    }
    if (scale > 0) chars.insert(decimalPointPosition, '.')
    chars.insert(0, sign)
    validateAndFormatNumber(chars.toString)
  }

  /** Transforms a string representation of an integer to a string representation of decimal
    * by adding a decimal point into the proper place
    *
    * @param intValue A number as an integer
    * @param scale    A scale - the number of digits to the right of decimal point separator
    * @return A string representation of decimal
    */
  private[cobol] def addDecimalPoint(intValue: String, scale: Int): String = {
    if (scale < 0) {
      throw new IllegalArgumentException(s"Invalid scele=$scale, should be greater or equal to zero.")
    }
    if (scale == 0) {
      intValue
    } else {
      val isNegative = intValue.length > 0 && intValue(0) == '-'
      if (isNegative) {
        if (intValue.length - 1 > scale) {
          val (part1, part2) = intValue.splitAt(intValue.length - scale)
          part1 + '.' + part2
        } else {
          "-" + "0." + "0" * (scale - intValue.length + 1) + intValue.splitAt(1)._2
        }
      } else {
        if (intValue.length > scale) {
          val (part1, part2) = intValue.splitAt(intValue.length - scale)
          part1 + '.' + part2
        } else {
          "0." + "0" * (scale - intValue.length) + intValue.splitAt(0)._2
        }
      }
    }
  }

  /** A generic decoder for 2s compliment binary numbers aka COMP
    *
    * @param bytes A byte array that represents the binary data
    * @return A string representation of the binary data
    */
  def decodeBinaryNumber(bytes: Array[Byte], bigEndian: Boolean, signed: Boolean, scale: Int = 0): String = {
    if (bytes.length == 0) {
      return "0"
    }

    val value = (signed, bigEndian, bytes.length) match {
      case (true, true, 1) => bytes(0)
      case (true, true, 2) => ((bytes(0) << 8) | (bytes(1) & 255)).toShort
      case (true, true, 4) => (bytes(0) << 24) | ((bytes(1) & 255) << 16) | ((bytes(2) & 255) << 8) | (bytes(3) & 255)
      case (true, true, 8) => ((bytes(0) & 255L) << 56) | ((bytes(1) & 255L) << 48) | ((bytes(2) & 255L) << 40) | ((bytes(3) & 255L) << 32) | ((bytes(4) & 255L) << 24) | ((bytes(5) & 255L) << 16) | ((bytes(6) & 255L) << 8) | (bytes(7) & 255L)
      case (true, false, 1) => bytes(0)
      case (true, false, 2) => ((bytes(1) << 8) | (bytes(0) & 255)).toShort
      case (true, false, 4) => (bytes(3) << 24) | ((bytes(2) & 255) << 16) | ((bytes(1) & 255) << 8) | (bytes(0) & 255)
      case (true, false, 8) => ((bytes(7) & 255L) << 56) | ((bytes(6) & 255L) << 48) | ((bytes(5) & 255L) << 40) | ((bytes(4) & 255L) << 32) | ((bytes(3) & 255L) << 24) | ((bytes(2) & 255L) << 16) | ((bytes(1) & 255L) << 8) | (bytes(0) & 255L)
      case (false, true, 1) => (bytes(0) & 255).toShort
      case (false, true, 2) => ((bytes(0) & 255) << 8) | (bytes(1) & 255)
      case (false, true, 4) => ((bytes(0) & 255L) << 24L) | ((bytes(1) & 255L) << 16L) | ((bytes(2) & 255L) << 8L) | (bytes(3) & 255L)
      case (false, false, 1) => (bytes(0) & 255).toShort
      case (false, false, 2) => ((bytes(1) & 255) << 8) | (bytes(0) & 255)
      case (false, false, 4) => ((bytes(3) & 255L) << 24L) | ((bytes(2) & 255L) << 16L) | ((bytes(1) & 255L) << 8L) | (bytes(0) & 255L)
      case _ =>
        // Generic arbitrary precision decoder
        val bigInt = (bigEndian, signed) match {
          case (false, false) => BigInt(1, bytes.reverse)
          case (false, true) => BigInt(bytes.reverse)
          case (true, false) => BigInt(1, bytes)
          case (true, true) => BigInt(bytes)
        }
        bigInt
    }
    addDecimalPoint(value.toString, scale)
  }

  /** A decoder for floating point numbers
    *
    * @param bytes A byte array that represents the binary data
    * @return A string representation of the binary data
    */
  def decodeFloatingPointNumber(bytes: Array[Byte], bigEndian: Boolean): String = {
    val bits = BitVector(bytes)
    val value = (bigEndian, bytes.length) match {
      case (true, 4) => floatB.decode(bits).require.value
      case (true, 8) => doubleB.decode(bits).require.value
      case (false, 4) => floatL.decode(bits).require.value
      case (false, 8) => doubleL.decode(bits).require.value
      case _ => throw new IllegalArgumentException(s"Illegal number of bytes to decode (${bytes.length}). Expected either 4 or 8 for floating point" +
        s" type.")
    }
    value.toString
  }

  /** Formats and validates a string as a number. Returns None if the string doesn't pass the validation **/
  private def validateAndFormatNumber(str: String): Option[String] = {
    val value = try {
      Some(BigDecimal(str).toString)
    } catch {
      case NonFatal(_) => None
    }
    value
  }

  /** Extracts record length from an XCOM 4 byte header.**/
  def extractXcomRecordSize(data: Array[Byte], byteIndex: Long = 0L): Int = {
    val xcomHeaderBlock = 4
    if (data.length < xcomHeaderBlock) {
      -1
    }
    else {
      val recordLength = (data(2) & 0xFF) + 256 * (data(3) & 0xFF)

      if (recordLength > 0) {
        if (recordLength > Constants.maxXcomRecordSize) {
          val xcomHeaders = data.map(_ & 0xFF).mkString(",")
          throw new IllegalStateException(s"XCOM headers too big (length = $recordLength > ${Constants.maxXcomRecordSize}). Headers = $xcomHeaders at $byteIndex.")
        }
        recordLength
      } else {
        val xcomHeaders = data.map(_ & 0xFF).mkString(",")
        throw new IllegalStateException(s"XCOM headers should never be zero ($xcomHeaders). Found zero size record at $byteIndex.")
      }
    }
  }
}
