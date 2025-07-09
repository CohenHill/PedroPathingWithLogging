package com.pedropathing.ftc.log;

import com.pedropathing.geometry.Pose;
import com.pedropathing.log.LogSubscriber;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A LogSubscriber implementation that writes logs in WPILOG format for AdvantageScope.
 * Logs primitive data types to a .wpilog file with automatic file rotation.
 *
 * @author Cohen Hill (original Kotlin implementation)
 */
public class WpiLogger implements LogSubscriber {
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;  // 5MB
    private static final int ROTATION_LIMIT = 5;                // Maximum number of rotated files
    private static final int BATCH_SIZE = 50;                  // Number of entries to batch before writing

    private final String path;
    private BufferedOutputStream stream;
    private final long startTime;
    private int nextChannelId = 0;
    private final Map<String, Integer> channelMap = new HashMap<>();
    private final Map<String, String> typeMap = new HashMap<>();
    private final Map<String, Object> dataBuffer = new HashMap<>();
    private final List<byte[]> logBatch = new ArrayList<>();
    private final BlockingQueue<byte[]> logQueue = new LinkedBlockingQueue<>();
    private final Thread loggerThread;
    private volatile boolean running = true;

    // Reusable buffers to reduce allocations
    private final ByteArrayOutputStream reusablePayload = new ByteArrayOutputStream();
    private final ByteBuffer reusableBuffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
    private final byte[] varintBuffer = new byte[10];

    public WpiLogger(String path) throws IOException {
        this.path = path;
        this.stream = new BufferedOutputStream(new FileOutputStream(path));
        this.startTime = System.nanoTime();

        // Start logger thread
        loggerThread = new Thread(this::processLogQueue);
        loggerThread.setDaemon(true);
        loggerThread.start();
    }

    @Override
    public void onLog(String key, Object value) {
        if (key != null) {
            dataBuffer.put(key, value);
        }
    }

    @Override
    public void update() {
        if (stream == null) {
            return; // Skip logging if stream is null
        }

        try {
            for (Map.Entry<String, Object> entry : dataBuffer.entrySet()) {
                log(entry.getKey(), entry.getValue());
            }
            flushBatch();
            dataBuffer.clear();
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }

    /**
     * Closes and flushes the logger output stream.
     */
    public void close() throws IOException {
        running = false;
        loggerThread.interrupt();
        try {
            loggerThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (stream != null) {
            flushBatch();
            stream.flush();
            stream.close();
            stream = null;
        }
    }

    /**
     * Process log entries from the queue in a background thread
     */
    private void processLogQueue() {
        try {
            while (running) {
                byte[] logEntry = logQueue.take();
                if (stream != null) {
                    stream.write(logEntry);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("Error processing log queue: " + e.getMessage());
        }
    }

    /**
     * Logs a value to a named channel. Automatically creates the channel if it doesn't exist.
     */
    private void log(String name, Object value) throws IOException {
        if (value == null) {
            return; // Skip null values
        }

        rotateFileIfNeeded();

        String type = getType(value);
        int id;
        if (channelMap.containsKey(name)) {
            Integer idObj = channelMap.get(name);
            id = (idObj != null) ? idObj : nextChannelId++;
        } else {
            int newId = nextChannelId++;
            channelMap.put(name, newId);
            typeMap.put(name, type);
            try {
                writeStartRecord(newId, name, type);
            } catch (IOException e) {
                System.err.println("Error writing start record: " + e.getMessage());
            }
            id = newId;
        }

        long timestamp = (System.nanoTime() - startTime) / 1000;  // Microseconds

        switch (type) {
            case "boolean":
                writeDataRecordBoolean(id, timestamp, (Boolean) value);
                break;
            case "int32":
                writeDataRecordInt32(id, timestamp, (Integer) value);
                break;
            case "int64":
                writeDataRecordInt64(id, timestamp, (Long) value);
                break;
            case "float":
                writeDataRecordFloat(id, timestamp, (Float) value);
                break;
            case "double":
                writeDataRecordDouble(id, timestamp, (Double) value);
                break;
            case "string":
                writeDataRecordString(id, timestamp, (String) value);
                break;
            default:
                if (value instanceof Pose) {
                    logPose(name, (Pose) value);
                } else {
                    throw new IllegalArgumentException("Unsupported type: " + type);
                }
        }
    }

    /**
     * Logs a Pose object to the log file.
     */
    private void logPose(String name, Pose pose) throws IOException {
        String type = "struct:Pose2d";
        int id;
        if (channelMap.containsKey(name)) {
            Integer idObj = channelMap.get(name);
            id = (idObj != null) ? idObj : nextChannelId++;
        } else {
            int newId = nextChannelId++;
            channelMap.put(name, newId);
            typeMap.put(name, type);
            try {
                writeStartRecord(newId, name, type);
            } catch (IOException e) {
                System.err.println("Error writing start record: " + e.getMessage());
            }
            id = newId;
        }

        long timestamp = (System.nanoTime() - startTime) / 1000;

        reusablePayload.reset();
        reusablePayload.write(encodeVarint(id, varintBuffer));
        reusablePayload.write(encodeVarint(timestamp, varintBuffer));

        reusableBuffer.clear();
        reusableBuffer.putFloat((float) pose.getX());
        reusableBuffer.putFloat((float) pose.getY());
        reusableBuffer.putFloat((float) pose.getHeading());

        reusablePayload.write(reusableBuffer.array(), 0, reusableBuffer.position());
        addToLogBatch(0x01, reusablePayload.toByteArray());
    }

    /**
     * Returns the WPILOG type string for a given value.
     */
    private String getType(Object value) {
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer) return "int32";
        if (value instanceof Long) return "int64";
        if (value instanceof Float) return "float";
        if (value instanceof Double) return "double";
        if (value instanceof String) return "string";
        if (value instanceof Pose) return "struct:Pose2d";
        throw new IllegalArgumentException("Unsupported type: " + value.getClass().getName());
    }

    /**
     * Writes a start record defining a new logging channel.
     */
    private void writeStartRecord(int id, String name, String type) throws IOException {
        reusablePayload.reset();
        reusablePayload.write(encodeVarint(id, varintBuffer));
        reusablePayload.write(name.getBytes(StandardCharsets.UTF_8));
        reusablePayload.write(0);
        reusablePayload.write(type.getBytes(StandardCharsets.UTF_8));
        reusablePayload.write(0);
        reusablePayload.write(0);
        reusablePayload.write(0);
        addToLogBatch(0x00, reusablePayload.toByteArray());
    }

    // Various data record writing methods

    private void writeDataRecordBoolean(int id, long timestamp, boolean value) throws IOException {
        reusablePayload.reset();
        reusablePayload.write(encodeVarint(id, varintBuffer));
        reusablePayload.write(encodeVarint(timestamp, varintBuffer));
        reusablePayload.write(value ? 1 : 0);
        addToLogBatch(0x01, reusablePayload.toByteArray());
    }

    private void writeDataRecordInt32(int id, long timestamp, int value) throws IOException {
        reusablePayload.reset();
        reusablePayload.write(encodeVarint(id, varintBuffer));
        reusablePayload.write(encodeVarint(timestamp, varintBuffer));

        reusableBuffer.clear();
        reusableBuffer.putInt(value);
        reusablePayload.write(reusableBuffer.array(), 0, 4);

        addToLogBatch(0x01, reusablePayload.toByteArray());
    }

    private void writeDataRecordInt64(int id, long timestamp, long value) throws IOException {
        reusablePayload.reset();
        reusablePayload.write(encodeVarint(id, varintBuffer));
        reusablePayload.write(encodeVarint(timestamp, varintBuffer));

        reusableBuffer.clear();
        reusableBuffer.putLong(value);
        reusablePayload.write(reusableBuffer.array(), 0, 8);

        addToLogBatch(0x01, reusablePayload.toByteArray());
    }

    private void writeDataRecordFloat(int id, long timestamp, float value) throws IOException {
        reusablePayload.reset();
        reusablePayload.write(encodeVarint(id, varintBuffer));
        reusablePayload.write(encodeVarint(timestamp, varintBuffer));

        reusableBuffer.clear();
        reusableBuffer.putFloat(value);
        reusablePayload.write(reusableBuffer.array(), 0, 4);

        addToLogBatch(0x01, reusablePayload.toByteArray());
    }

    private void writeDataRecordDouble(int id, long timestamp, double value) throws IOException {
        reusablePayload.reset();
        reusablePayload.write(encodeVarint(id, varintBuffer));
        reusablePayload.write(encodeVarint(timestamp, varintBuffer));

        reusableBuffer.clear();
        reusableBuffer.putDouble(value);
        reusablePayload.write(reusableBuffer.array(), 0, 8);

        addToLogBatch(0x01, reusablePayload.toByteArray());
    }

    private void writeDataRecordString(int id, long timestamp, String value) throws IOException {
        if (value == null) {
            value = "";
        }

        reusablePayload.reset();
        reusablePayload.write(encodeVarint(id, varintBuffer));
        reusablePayload.write(encodeVarint(timestamp, varintBuffer));
        reusablePayload.write(value.getBytes(StandardCharsets.UTF_8));
        reusablePayload.write(0);  // Null terminator
        addToLogBatch(0x01, reusablePayload.toByteArray());
    }

    /**
     * Adds a record to the log batch
     */
    private void addToLogBatch(int type, byte[] payload) throws IOException {
        byte[] record = new byte[1 + varintBuffer.length + payload.length];
        record[0] = (byte) type;

        int varintLength = encodeVarintToArray(payload.length, varintBuffer);
        System.arraycopy(varintBuffer, 0, record, 1, varintLength);
        System.arraycopy(payload, 0, record, 1 + varintLength, payload.length);

        logBatch.add(record);
        if (logBatch.size() >= BATCH_SIZE) {
            flushBatch();
        }
    }

    /**
     * Writes a complete WPILOG record to the stream.
     */
    private void writeRecord(int type, byte[] payload) throws IOException {
        if (stream != null) {
            stream.write(type);
            stream.write(encodeVarint(payload.length, varintBuffer));
            stream.write(payload);
        }
    }

    /**
     * Flushes the log batch to the output stream
     */
    private void flushBatch() throws IOException {
        for (byte[] record : logBatch) {
            logQueue.offer(record);
        }
        logBatch.clear();
    }

    /**
     * Encodes a Long as a variable-length integer (varint) into the provided buffer.
     * Returns the portion of the buffer containing the encoded varint.
     */
    private byte[] encodeVarint(long value, byte[] buffer) {
        int size = encodeVarintToArray(value, buffer);
        byte[] result = new byte[size];
        System.arraycopy(buffer, 0, result, 0, size);
        return result;
    }

    /**
     * Encodes a Long as a variable-length integer (varint) into the provided buffer.
     * Returns the number of bytes written.
     */
    private int encodeVarintToArray(long value, byte[] buffer) {
        int index = 0;
        long v = value;
        while ((v & ~0x7F) != 0) {
            buffer[index++] = (byte)((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buffer[index++] = (byte)v;
        return index;
    }

    /**
     * Returns a rotated file name like "log.wpilog.1", "log.wpilog.2", etc.
     */
    private String rotatedPath(int index) {
        int dot = path.lastIndexOf('.');
        if (dot != -1) {
            return path.substring(0, dot) + "." + index + path.substring(dot);
        } else {
            return path + "." + index;
        }
    }

    /**
     * Rotates the log file if it exceeds the maximum size.
     */
    private void rotateFileIfNeeded() throws IOException {
        File logFile = new File(path);
        if (!logFile.exists()) return;
        if (logFile.length() >= MAX_FILE_SIZE) {
            close();

            // Rotate files
            for (int i = ROTATION_LIMIT; i >= 2; i--) {
                File older = new File(rotatedPath(i - 1));
                File newer = new File(rotatedPath(i));
                if (older.exists()) older.renameTo(newer);
            }
            logFile.renameTo(new File(rotatedPath(1)));

            // Create new file and reset state
            try {
                stream = new BufferedOutputStream(new FileOutputStream(path));
                running = true;
                loggerThread.start();
            } catch (IOException e) {
                System.err.println("Error creating log file: " + e.getMessage());
                stream = null;
            }
            channelMap.clear();
            typeMap.clear();
            nextChannelId = 0;
        }
    }
}
