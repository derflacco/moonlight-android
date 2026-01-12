package com.limelight.binding.video;

import android.os.SystemClock;

class VideoStats {

    long decoderTimeMs;
    long totalTimeMs;
    long endToEndLatencyMs; // Tracks old latency metric (uptime - PTS)
    int totalFrames;
    int totalFramesReceived;
    int totalFramesRendered;
    int frameLossEvents;
    int framesLost;
    char minHostProcessingLatency;
    char maxHostProcessingLatency;
    int totalHostProcessingLatency;
    int framesWithHostProcessingLatency;
    long measurementStartTimestamp;
    public long decoderSamples = 0;
    public long decoderMisses = 0;

    void add(VideoStats other) {
        this.decoderTimeMs += other.decoderTimeMs;
        this.totalTimeMs += other.totalTimeMs;
        this.endToEndLatencyMs += other.endToEndLatencyMs;
        this.totalFrames += other.totalFrames;
        this.totalFramesReceived += other.totalFramesReceived;
        this.totalFramesRendered += other.totalFramesRendered;
        this.frameLossEvents += other.frameLossEvents;
        this.framesLost += other.framesLost;

        // Decode latency sample counters
        this.decoderSamples += other.decoderSamples;
        this.decoderMisses += other.decoderMisses;

        if (this.minHostProcessingLatency == 0) {
            this.minHostProcessingLatency = other.minHostProcessingLatency;
        } else {
            this.minHostProcessingLatency = (char) Math.min(this.minHostProcessingLatency, other.minHostProcessingLatency);
        }
        this.maxHostProcessingLatency = (char) Math.max(this.maxHostProcessingLatency, other.maxHostProcessingLatency);
        this.totalHostProcessingLatency += other.totalHostProcessingLatency;
        this.framesWithHostProcessingLatency += other.framesWithHostProcessingLatency;

        if (this.measurementStartTimestamp == 0) {
            this.measurementStartTimestamp = other.measurementStartTimestamp;
        }

        assert other.measurementStartTimestamp >= this.measurementStartTimestamp;
    }

    void copy(VideoStats other) {
        this.decoderTimeMs = other.decoderTimeMs;
        this.totalTimeMs = other.totalTimeMs;
        this.endToEndLatencyMs = other.endToEndLatencyMs;
        this.totalFrames = other.totalFrames;
        this.totalFramesReceived = other.totalFramesReceived;
        this.totalFramesRendered = other.totalFramesRendered;
        this.frameLossEvents = other.frameLossEvents;
        this.framesLost = other.framesLost;
        this.minHostProcessingLatency = other.minHostProcessingLatency;
        this.maxHostProcessingLatency = other.maxHostProcessingLatency;
        this.totalHostProcessingLatency = other.totalHostProcessingLatency;
        this.framesWithHostProcessingLatency = other.framesWithHostProcessingLatency;
        this.measurementStartTimestamp = other.measurementStartTimestamp;

        // Decode latency sample accounting
        this.decoderSamples = other.decoderSamples;
        this.decoderMisses = other.decoderMisses;
    }

    void clear() {
        this.decoderTimeMs = 0;
        this.totalTimeMs = 0;
        this.endToEndLatencyMs = 0;
        this.totalFrames = 0;
        this.totalFramesReceived = 0;
        this.totalFramesRendered = 0;
        this.frameLossEvents = 0;
        this.framesLost = 0;
        this.minHostProcessingLatency = 0;
        this.maxHostProcessingLatency = 0;
        this.totalHostProcessingLatency = 0;
        this.framesWithHostProcessingLatency = 0;
        this.measurementStartTimestamp = 0;

        // Decode latency sample accounting
        this.decoderSamples = 0;
        this.decoderMisses = 0;
    }

    VideoStatsFps getFps() {
        float elapsed = (SystemClock.uptimeMillis() - this.measurementStartTimestamp) / (float) 1000;

        VideoStatsFps fps = new VideoStatsFps();
        if (elapsed > 0) {
            fps.totalFps = this.totalFrames / elapsed;
            fps.receivedFps = this.totalFramesReceived / elapsed;
            fps.renderedFps = this.totalFramesRendered / elapsed;
        }
        return fps;
    }
}

class VideoStatsFps {

    float totalFps;
    float receivedFps;
    float renderedFps;
}