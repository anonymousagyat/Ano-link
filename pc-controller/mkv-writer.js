const fs = require('fs');
const path = require('path');

// Helper to encode EBML variable-length integer (VINT)
function encodeVInt(value) {
    if (value < 0x7F) {
        return Buffer.from([0x80 | value]);
    } else if (value < 0x3FFF) {
        return Buffer.from([(0x40 | (value >> 8)), value & 0xFF]);
    } else if (value < 0x1FFFFF) {
        return Buffer.from([(0x20 | (value >> 16)), (value >> 8) & 0xFF, value & 0xFF]);
    } else if (value < 0x0FFFFFFF) {
        return Buffer.from([(0x10 | (value >> 24)), (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF]);
    } else {
        const buf = Buffer.alloc(8);
        buf.writeBigUInt64BE(0x0100000000000000n | BigInt(value));
        return buf;
    }
}

function ebmlElement(idBytes, data) {
    const id = Buffer.isBuffer(idBytes) ? idBytes : Buffer.from(idBytes);
    const body = Buffer.isBuffer(data) ? data : (typeof data === 'string' ? Buffer.from(data, 'utf8') : Buffer.from(data));
    const size = encodeVInt(body.length);
    return Buffer.concat([id, size, body]);
}

function ebmlInt(idBytes, val) {
    let buf;
    if (val <= 0xFF) {
        buf = Buffer.from([val]);
    } else if (val <= 0xFFFF) {
        buf = Buffer.alloc(2);
        buf.writeUInt16BE(val);
    } else if (val <= 0xFFFFFF) {
        buf = Buffer.from([(val >> 16) & 0xFF, (val >> 8) & 0xFF, val & 0xFF]);
    } else {
        buf = Buffer.alloc(4);
        buf.writeUInt32BE(val);
    }
    return ebmlElement(idBytes, buf);
}

function ebmlString(idBytes, str) {
    return ebmlElement(idBytes, Buffer.from(str, 'utf8'));
}

class MkvWriter {
    /**
     * @param {string} filePath - Absolute path to target .mkv file
     * @param {number} width - Video frame width (default: 540)
     * @param {number} height - Video frame height (default: 1200)
     */
    constructor(filePath, width = 540, height = 1200) {
        this.filePath = filePath;
        this.width = width || 540;
        this.height = height || 1200;
        
        // Ensure parent directory exists
        const dir = path.dirname(filePath);
        if (!fs.existsSync(dir)) {
            fs.mkdirSync(dir, { recursive: true });
        }

        this.stream = fs.createWriteStream(filePath, { highWaterMark: 1024 * 1024 });
        this.startTime = 0;
        this.frameCount = 0;
        this.totalBytes = 0;
        this.currentClusterTime = -1;
        this.lastTimestamp = 0;
        this.isClosed = false;

        this.writeHeader();
    }

    writeHeader() {
        // 1. EBML Header (0x1A45DFA3)
        const ebmlHeader = ebmlElement([0x1A, 0x45, 0xDF, 0xA3], Buffer.concat([
            ebmlInt([0x42, 0x86], 1),             // EBMLVersion: 1
            ebmlInt([0x42, 0xF7], 1),             // EBMLReadVersion: 1
            ebmlInt([0x42, 0xF2], 4),             // EBMLMaxIDLength: 4
            ebmlInt([0x42, 0xF3], 8),             // EBMLMaxSizeLength: 8
            ebmlString([0x42, 0x82], "matroska"), // DocType: "matroska"
            ebmlInt([0x42, 0x87], 4),             // DocTypeVersion: 4
            ebmlInt([0x42, 0xF5], 2)              // DocTypeReadVersion: 2
        ]));
        this.stream.write(ebmlHeader);

        // 2. Segment Element (0x18538067) with Unknown Size (0x01FFFFFFFFFFFFFF)
        this.stream.write(Buffer.from([0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]));

        // 3. Segment Info Element (0x1549A966)
        const info = ebmlElement([0x15, 0x49, 0xA9, 0x66], Buffer.concat([
            ebmlInt([0x2A, 0xD7, 0xB1], 1000000),         // TimestampScale: 1,000,000 ns (1ms per tick)
            ebmlString([0x4D, 0x80], "AnoLink PC Engine"), // MuxingApp
            ebmlString([0x57, 0x41], "Ano-Link WebRTC")    // WritingApp
        ]));
        this.stream.write(info);

        // 4. Tracks Element (0x1654AE6B)
        const videoSettings = ebmlElement([0xE0], Buffer.concat([
            ebmlInt([0xB0], this.width),                 // PixelWidth
            ebmlInt([0xBA], this.height),                // PixelHeight
            ebmlInt([0x54, 0xB0], this.width),           // DisplayWidth
            ebmlInt([0x54, 0xBA], this.height)           // DisplayHeight
        ]));

        const trackEntry = ebmlElement([0xAE], Buffer.concat([
            ebmlInt([0xD7], 1),                          // TrackNumber: 1
            ebmlInt([0x73, 0xC5], 1),                    // TrackUID: 1
            ebmlInt([0x83], 1),                          // TrackType: 1 (Video)
            ebmlInt([0x88], 1),                          // FlagDefault: 1
            ebmlString([0x86], "V_MJPEG"),               // CodecID: V_MJPEG
            videoSettings
        ]));

        const tracks = ebmlElement([0x16, 0x54, 0xAE, 0x6B], trackEntry);
        this.stream.write(tracks);
    }

    /**
     * Append a raw JPEG frame buffer to the recording stream
     * @param {Buffer} jpegBuffer - Raw JPEG frame bytes
     * @param {number} [timestampMs] - Millisecond timestamp
     */
    addFrame(jpegBuffer, timestampMs = Date.now()) {
        if (this.isClosed || !jpegBuffer || jpegBuffer.length === 0) return;

        if (this.startTime === 0) {
            this.startTime = timestampMs;
        }
        const relTime = Math.max(0, timestampMs - this.startTime);

        // Start new cluster every ~5000ms or on the very first frame
        if (this.currentClusterTime < 0 || (relTime - this.currentClusterTime) >= 5000) {
            this.currentClusterTime = relTime;
            // Write Cluster Element (0x1F43B675) with unknown size (0x01FFFFFFFFFFFFFF)
            this.stream.write(Buffer.from([0x1F, 0x43, 0xB6, 0x75, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]));
            // Cluster Timestamp (0xE7)
            this.stream.write(ebmlInt([0xE7], relTime));
        }

        // Relative timecode inside cluster (clamped to int16 range)
        const blockRelTime = Math.max(0, Math.min(32767, relTime - this.currentClusterTime));

        // SimpleBlock header: Track 1 (0x81), 16-bit timecode, Flags 0x80 (Keyframe)
        const blockHeader = Buffer.alloc(4);
        blockHeader[0] = 0x81;
        blockHeader.writeInt16BE(blockRelTime, 1);
        blockHeader[3] = 0x80;

        // SimpleBlock Element (0xA3)
        const blockPayload = Buffer.concat([blockHeader, jpegBuffer]);
        const blockElement = ebmlElement([0xA3], blockPayload);
        this.stream.write(blockElement);

        this.frameCount++;
        this.totalBytes += jpegBuffer.length;
        this.lastTimestamp = relTime;
    }

    /**
     * Get live recording metrics
     */
    getStats() {
        const elapsedSec = Math.max(0, Math.round((this.lastTimestamp || (this.startTime ? Date.now() - this.startTime : 0)) / 1000));
        const sizeMb = (this.totalBytes / (1024 * 1024)).toFixed(1) + ' MB';
        return {
            durationSec: elapsedSec,
            framesCount: this.frameCount,
            totalBytes: this.totalBytes,
            sizeMb
        };
    }

    /**
     * Finalize the recording and close the file
     * @returns {Promise<object>}
     */
    finalize() {
        if (this.isClosed) {
            return Promise.resolve({
                filePath: this.filePath,
                filename: path.basename(this.filePath),
                frameCount: this.frameCount,
                totalBytes: this.totalBytes,
                durationSec: Math.round(this.lastTimestamp / 1000),
                sizeMb: (this.totalBytes / (1024 * 1024)).toFixed(2) + ' MB'
            });
        }
        this.isClosed = true;

        return new Promise((resolve, reject) => {
            this.stream.end(() => {
                const durationSec = Math.round((this.lastTimestamp || 0) / 1000);
                const sizeMb = (this.totalBytes / (1024 * 1024)).toFixed(2) + ' MB';
                resolve({
                    filePath: this.filePath,
                    filename: path.basename(this.filePath),
                    frameCount: this.frameCount,
                    totalBytes: this.totalBytes,
                    durationSec,
                    sizeMb
                });
            });
            this.stream.on('error', reject);
        });
    }
}

module.exports = { MkvWriter };
