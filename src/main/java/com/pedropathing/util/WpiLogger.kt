package com.pedropathing.util

import com.pedropathing.localization.Pose
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A WPILOG logger compatible with AdvantageScope.
 * Logs primitive data types to a .wpilog file on the Control Hub or device.
 *
 * Supports: Boolean, Int (int32), Long (int64), Float, Double, String
 * Automatically rotates files when they exceed 5MB.
 *
 * @author Cohen Hill
 * @version 1.0, 2025-07-06
 */
class WpiLogger(private val path: String) {
    private val maxFileSize: Long = 5 * 1024 * 1024  // 5MB
    private val rotationLimit: Int = 5               // Maximum number of rotated files
    private var stream: FileOutputStream = FileOutputStream(path)
    private val startTime = System.nanoTime()
    private var nextChannelId: Int = 0
    private val channelMap = mutableMapOf<String, Int>()
    private val typeMap = mutableMapOf<String, String>()

    /**
     * Closes and flushes the logger output stream.
     */
    fun close() {
        stream.flush()
        stream.close()
    }

    /**
     * Logs a value to a named channel. Automatically creates the channel if it doesn't exist.
     *
     * @param name The channel name.
     * @param value A supported primitive value.
     */
    fun log(name: String, value: Any) {
        rotateFileIfNeeded()

        val type = getType(value)
        val id = channelMap.getOrPut(name) {
            val newId = nextChannelId++
            typeMap[name] = type
            writeStartRecord(newId, name, type)
            newId
        }

        val timestamp = (System.nanoTime() - startTime) / 1000  // Microseconds

        when (type) {
            "boolean" -> writeDataRecordBoolean(id, timestamp, value as Boolean)
            "int32"   -> writeDataRecordInt32(id, timestamp, value as Int)
            "int64"   -> writeDataRecordInt64(id, timestamp, value as Long)
            "float"   -> writeDataRecordFloat(id, timestamp, value as Float)
            "double"  -> writeDataRecordDouble(id, timestamp, value as Double)
            "string"  -> writeDataRecordString(id, timestamp, value as String)
            else      -> error("Unsupported type: $type")
        }
    }
    fun logPose(name: String, pose: Pose) {
        val type = "struct:Pose2d"
        val id = channelMap.getOrPut(name) {
            val newId = nextChannelId++
            typeMap[name] = type
            writeStartRecord(newId, name, type)
            newId
        }

        val timestamp = (System.nanoTime() - startTime) / 1000

        val payload = ByteArrayOutputStream()
        payload.write(encodeVarint(id))
        payload.write(encodeVarint(timestamp))

        val poseBytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(pose.x.toFloat())
            .putFloat(pose.y.toFloat())
            .putFloat(pose.heading.toFloat())
            .array()

        payload.write(poseBytes)
        writeRecord(0x01, payload.toByteArray())
    }

    /**
     * Returns the WPILOG type string for a given value.
     */
    private fun getType(value: Any): String = when (value) {
        is Boolean -> "boolean"
        is Int     -> "int32"
        is Long    -> "int64"
        is Float   -> "float"
        is Double  -> "double"
        is String  -> "string"
        else       -> error("Unsupported type: ${value::class}")
    }

    /**
     * Writes a start record defining a new logging channel.
     */
    private fun writeStartRecord(id: Int, name: String, type: String) {
        val payload = ByteArrayOutputStream()
        payload.write(encodeVarint(id))
        payload.write(name.toByteArray(Charsets.UTF_8))
        payload.write(0)
        payload.write(type.toByteArray(Charsets.UTF_8))
        payload.write(0)
        payload.write(0)
        payload.write(0)
        writeRecord(0x00, payload.toByteArray())
    }

    /**
     * Writes a data record for a Boolean value.
     */
    private fun writeDataRecordBoolean(id: Int, timestamp: Long, value: Boolean) {
        val payload = ByteArrayOutputStream()
        payload.write(encodeVarint(id))
        payload.write(encodeVarint(timestamp))
        payload.write(if (value) 1 else 0)
        writeRecord(0x01, payload.toByteArray())
    }

    /**
     * Writes a data record for a 32-bit integer (Int).
     */
    private fun writeDataRecordInt32(id: Int, timestamp: Long, value: Int) {
        val payload = ByteArrayOutputStream()
        payload.write(encodeVarint(id))
        payload.write(encodeVarint(timestamp))
        payload.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        writeRecord(0x01, payload.toByteArray())
    }

    /**
     * Writes a data record for a 64-bit integer (Long).
     */
    private fun writeDataRecordInt64(id: Int, timestamp: Long, value: Long) {
        val payload = ByteArrayOutputStream()
        payload.write(encodeVarint(id))
        payload.write(encodeVarint(timestamp))
        payload.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
        writeRecord(0x01, payload.toByteArray())
    }

    /**
     * Writes a data record for a Float value.
     */
    private fun writeDataRecordFloat(id: Int, timestamp: Long, value: Float) {
        val payload = ByteArrayOutputStream()
        payload.write(encodeVarint(id))
        payload.write(encodeVarint(timestamp))
        payload.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        writeRecord(0x01, payload.toByteArray())
    }

    /**
     * Writes a data record for a Double value.
     */
    private fun writeDataRecordDouble(id: Int, timestamp: Long, value: Double) {
        val payload = ByteArrayOutputStream()
        payload.write(encodeVarint(id))
        payload.write(encodeVarint(timestamp))
        payload.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())
        writeRecord(0x01, payload.toByteArray())
    }

    /**
     * Writes a data record for a String value (null-terminated).
     */
    private fun writeDataRecordString(id: Int, timestamp: Long, value: String) {
        val payload = ByteArrayOutputStream()
        payload.write(encodeVarint(id))
        payload.write(encodeVarint(timestamp))
        payload.write(value.toByteArray(Charsets.UTF_8))
        payload.write(0)  // Null terminator
        writeRecord(0x01, payload.toByteArray())
    }

    /**
     * Writes a complete WPILOG record to the stream.
     */
    private fun writeRecord(type: Int, payload: ByteArray) {
        stream.write(type)
        stream.write(encodeVarint(payload.size))
        stream.write(payload)
    }

    /**
     * Encodes a Long as a variable-length integer (varint).
     */
    private fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (v and 0x7F.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
        return out.toByteArray()
    }

    /**
     * Encodes an Int as a varint.
     */
    private fun encodeVarint(value: Int): ByteArray {
        return encodeVarint(value.toLong())
    }

    /**
     * Returns a rotated file name like "log.wpilog.1", "log.wpilog.2", etc.
     */
    private fun rotatedPath(index: Int): String {
        val dot = path.lastIndexOf('.')
        return if (dot != -1) {
            path.substring(0, dot) + ".$index" + path.substring(dot)
        } else {
            "$path.$index"
        }
    }

    /**
     * Rotates the log file if it exceeds the maximum size.
     */
    private fun rotateFileIfNeeded() {
        val logFile = File(path)
        if (!logFile.exists()) return
        if (logFile.length() >= maxFileSize) {
            close()
            for (i in rotationLimit downTo 2) {
                val older = File(rotatedPath(i - 1))
                val newer = File(rotatedPath(i))
                if (older.exists()) older.renameTo(newer)
            }
            logFile.renameTo(File(rotatedPath(1)))
            stream = FileOutputStream(path)
            channelMap.clear()
            typeMap.clear()
            nextChannelId = 0
        }
    }
}
