# 🎥 HLS Streaming & Video Pipeline — Deep Dive

> **This is the heart of StreamLite.** Every question about "how does the streaming work?" starts here.

---

## 1. What is HLS (HTTP Live Streaming)?

**HLS** is an adaptive, HTTP-based streaming protocol originally developed by Apple. It's now an industry standard supported by all major browsers natively (Safari, Chrome, Firefox, Edge).

### How HLS Works — The Playlist Model

Instead of a single large video file, HLS breaks the video into **small chunks** and provides a **manifest/playlist** that tells the player where to find them.

```
Browser requests → playlist.m3u8 (the manifest)
                 ↓
Browser reads manifest → download playlist0.ts (first 10 seconds)
                       → download playlist1.ts (next 10 seconds)
                       → download playlist2.ts (next 10 seconds)
                       → ... (only downloads what's needed)
```

### A Real `.m3u8` Playlist File (what gets generated):

```m3u8
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:11
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:10.500000,
playlist0.ts
#EXTINF:10.000000,
playlist1.ts
#EXTINF:10.000000,
playlist2.ts
#EXTINF:9.800000,
playlist3.ts
#EXT-X-ENDLIST
```

**Key tags explained:**
- `#EXTM3U` — File type declaration (M3U extended)
- `#EXT-X-VERSION:3` — HLS protocol version
- `#EXT-X-TARGETDURATION:11` — Max segment duration in seconds
- `#EXT-X-MEDIA-SEQUENCE:0` — Start sequence number
- `#EXTINF:10.5,` — This segment is 10.5 seconds long
- `playlist0.ts` — Filename of the MPEG-TS segment
- `#EXT-X-ENDLIST` — VOD indicator (all segments listed, not live)

### `.ts` Files — MPEG-TS (Transport Stream)

Each `.ts` file is an **MPEG-2 Transport Stream** — the container format used for broadcast television. It was chosen for HLS because:
- Resilient to packet loss (critical for streaming)
- Each packet (188 bytes) can be decoded independently
- No need of the full file to decode — you can join/leave mid-stream

---

## 2. FFmpeg — The Video Processing Engine

**FFmpeg** is a free, open-source multimedia framework that can decode, encode, transcode, mux, demux, stream, and filter audio/video. It's used by YouTube, Twitch, Netflix, and virtually every streaming service.

### The Exact FFmpeg Command Used in StreamLite

```bash
ffmpeg -i "videos/input/videoplayback.mp4" \
    -c:v copy \
    -c:a copy \
    -start_number 0 \
    -hls_time 10 \
    -hls_list_size 0 \
    -f hls \
    "videos/output/playlist.m3u8"
```

### Breaking Down Every Flag:

| Flag | Meaning | Why Used |
|---|---|---|
| `-i "input.mp4"` | Input file | The uploaded video |
| `-c:v copy` | Copy video codec | **No re-encoding** — much faster, no quality loss |
| `-c:a copy` | Copy audio codec | Same — preserves original audio |
| `-start_number 0` | First segment is `playlist0.ts` | Consistent naming starting from 0 |
| `-hls_time 10` | Each segment is ~10 seconds | Balance between seek granularity and file count |
| `-hls_list_size 0` | List ALL segments in playlist | `0` = VOD (all segments), Live would use a rolling window |
| `-f hls` | Force HLS output format | Tells FFmpeg to write `.m3u8` + `.ts` files |

### Why `-c:v copy` instead of re-encoding?

Re-encoding (e.g., `-c:v libx264`) would:
1. Take **10x–100x longer** (1-minute video = minutes to process)
2. Potentially introduce quality artifacts
3. Be CPU-intensive

With `-c:v copy`, FFmpeg just **remuxes** — it splits the bitstream without touching the encoded data. A 100MB video segments in seconds.

**⚠️ Trade-off:** If the source video uses a codec not supported by HLS (e.g., VP9), you'd need to re-encode to H.264.

---

## 3. The StreamingController — Complete Code Analysis

### 3.1 Video Upload Endpoint (`POST /upload`)

```java
@PostMapping("/upload")
public ResponseEntity<?> uploadSpecificVideo(
    @RequestParam("file") MultipartFile file, 
    RedirectAttributes redirectAttributes
) {
    String baseDir = System.getProperty("user.dir") + 
                     File.separator + "videos" + 
                     File.separator + "input";
    
    // 1. Ensure input directory exists
    File inputDir = new File(baseDir);
    if (!inputDir.exists()) {
        inputDir.mkdirs();  // Creates intermediate directories too
    }
    
    // 2. Validate file type
    if (!file.getOriginalFilename().endsWith(".mp4")) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", "Only MP4 files are supported."));
    }
    
    // 3. Write file safely using Files.copy (NOT file.transferTo())
    File dest = new File(inputDir, file.getOriginalFilename());
    try (var inputStream = file.getInputStream()) {
        Files.copy(inputStream, dest.toPath(), 
                   StandardCopyOption.REPLACE_EXISTING);
    }
    
    // 4. Windows file handle release delay
    Thread.sleep(300);
    
    // 5. Invoke FFmpeg segmentation
    ProcessBuilder processBuilder = new ProcessBuilder(
        "segment_video.bat", dest.getAbsolutePath()
    );
    processBuilder.directory(new File(System.getProperty("user.dir")));
    processBuilder.inheritIO();  // Show FFmpeg logs in console
    Process process = processBuilder.start();
    int exitCode = process.waitFor();
    
    // 6. Save video metadata to PostgreSQL
    Video video = new Video();
    video.setTitle(file.getOriginalFilename());
    video.setUploaderId("uploader123");
    video.setUploadDate(LocalDateTime.now().toString());
    video.setHlsPath("videos/output/playlist.m3u8");
    videoRepo.save(video);
    
    return ResponseEntity.ok(Map.of(
        "message", "Video uploaded and segmented successfully!",
        "videoId", video.getId(),
        "playlist", video.getHlsPath()
    ));
}
```

**Key decisions explained:**

1. **`System.getProperty("user.dir")`** — Gets the project's working directory dynamically. This makes the path OS-agnostic and avoids hard-coded paths.

2. **`Files.copy()` instead of `file.transferTo()`** — `transferTo()` relies on Tomcat's internal temp directory, which had permission issues. `Files.copy()` uses a direct `InputStream`, bypassing Tomcat's intermediate handling. (See Bug Report in existing docs)

3. **`Thread.sleep(300)`** — On Windows, after writing a file, the OS doesn't immediately release the file handle. Without this 300ms pause, FFmpeg would fail with "file in use" error. This is a documented Windows-specific behavior.

4. **`processBuilder.inheritIO()`** — Routes FFmpeg's stdout/stderr to the JVM's console. Makes debugging trivial — FFmpeg's progress is visible directly.

5. **`process.waitFor()`** — **Synchronous** execution. The HTTP response is held until FFmpeg completes. This is correct for a single-file upload but would need to become async (CompletableFuture + Kafka notification) for production.

### 3.2 HLS Playlist Endpoint (`GET /stream/{id}/playlist.m3u8`)

```java
@GetMapping("/stream/{id}/playlist.m3u8")
public ResponseEntity<byte[]> getHlsPlaylist(@PathVariable String id) {
    Optional<Video> videoOpt = videoRepo.findById(id);
    if (videoOpt.isEmpty()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    
    Video video = videoOpt.get();
    Path playlistPath = Paths.get(video.getHlsPath());
    
    if (!Files.exists(playlistPath)) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    
    byte[] playlistBytes = Files.readAllBytes(playlistPath);
    return ResponseEntity.ok()
        .header("Content-Type", "application/vnd.apple.mpegurl")
        .body(playlistBytes);
}
```

**MIME type `application/vnd.apple.mpegurl`** — This is the official IANA-registered MIME type for `.m3u8` files. Browsers won't play HLS without this correct content-type header.

### 3.3 HLS Segment Endpoint with Redis Cache (`GET /stream/{id}/{fileName}`)

```java
@GetMapping("/stream/{id}/{fileName}")
public ResponseEntity<byte[]> streamVideoSegment(
    @PathVariable String id, 
    @PathVariable String fileName
) {
    // STEP 1: Check Redis cache
    String cacheKey = id + ":" + fileName;  // e.g., "abc-123:playlist0.ts"
    byte[] cachedSegment = redisTemplate.opsForValue().get(cacheKey);
    
    if (cachedSegment != null) {
        System.out.println("Cache hit for segment: " + cacheKey);
        return ResponseEntity.ok()
            .header("Content-Type", "video/mp2t")
            .body(cachedSegment);
    }
    
    // STEP 2: Cache miss — read from disk
    System.out.println("Cache miss for segment: " + cacheKey);
    
    Optional<Video> videoOpt = videoRepo.findById(id);
    if (videoOpt.isEmpty()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    
    Video video = videoOpt.get();
    Path hlsFilePath = Paths.get(video.getHlsPath());   // e.g., videos/output/playlist.m3u8
    Path hlsDir = hlsFilePath.getParent();               // e.g., videos/output/
    Path filePath = hlsDir.resolve(fileName);             // e.g., videos/output/playlist0.ts
    
    if (!Files.exists(filePath)) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(("Segment not found: " + fileName).getBytes());
    }
    
    byte[] videoBytes = Files.readAllBytes(filePath);
    
    // STEP 3: Store in Redis with 1-hour TTL
    redisTemplate.opsForValue().set(
        cacheKey, videoBytes, 1, java.util.concurrent.TimeUnit.HOURS
    );
    
    return ResponseEntity.ok()
        .header("Content-Type", "video/mp2t")
        .body(videoBytes);
}
```

**MIME type `video/mp2t`** — MPEG-2 Transport Stream. This is the correct content-type for `.ts` segment files.

**Cache key design:** `"videoId:segmentFileName"` e.g., `"d3b4c5:playlist0.ts"` — This scoping ensures:
- Different videos don't collide (videoId prefix)
- Same segment from same video hits the same key (consistent caching)

**1-hour TTL:** After 1 hour, the segment is evicted from Redis. This balances memory usage with cache effectiveness. For a live event, TTL would be much shorter (minutes).

---

## 4. The Upload → Stream Flow (End to End)

```
[Browser/Postman]
    │
    ├─ POST /upload (multipart/form-data, file=video.mp4)
    │       │
    │       ├─ Files.copy() → videos/input/videoplayback.mp4
    │       ├─ Thread.sleep(300ms)  [Windows file handle release]
    │       ├─ ProcessBuilder runs segment_video.bat
    │       │       └─ FFmpeg: -i input.mp4 -c:v copy -c:a copy -hls_time 10 -f hls
    │       │               → videos/output/playlist.m3u8
    │       │               → videos/output/playlist0.ts
    │       │               → videos/output/playlist1.ts ... (N segments)
    │       ├─ videoRepo.save(video)  [PostgreSQL: id, title, hlsPath]
    │       └─ HTTP 200: { videoId, playlist }
    │
    ├─ GET /stream/{videoId}/playlist.m3u8
    │       └─ Reads playlist.m3u8 from disk → HTTP 200 (application/vnd.apple.mpegurl)
    │              ↓
    │         Browser's hls.js / video.js parses the manifest
    │
    └─ GET /stream/{videoId}/playlist0.ts  (repeated for each segment)
            ├─ Check Redis: key = "videoId:playlist0.ts"
            │   ├─ HIT → return bytes immediately (~1ms)
            │   └─ MISS → read disk → store in Redis → return bytes (~10ms)
            └─ HTTP 200 (video/mp2t)
```

---

## 5. Interview Questions on HLS — Prepared Answers

**Q: What is HLS and why did you use it?**
> HLS (HTTP Live Streaming) is an adaptive streaming protocol that breaks video into small segments (typically 2–10 seconds) described by an M3U8 playlist. I used it because it's natively supported by all browsers, works over standard HTTP/CDN infrastructure, supports random seek operations, and enables adaptive bitrate in the future. It's what YouTube and Netflix use.

**Q: What's the difference between live and VOD HLS?**
> In VOD, the playlist contains `#EXT-X-ENDLIST` and lists all segments. The player knows the full duration.  
> In Live, the playlist is a rolling window — new segments are added, old ones removed. The player polls the playlist every few seconds. No `#EXT-X-ENDLIST` tag.

**Q: What is MPEG-TS and why does HLS use it?**
> MPEG-2 Transport Stream is a broadcast-grade container designed for error-prone transmission. Each 188-byte packet can be independently decoded, making it resilient to packet loss. That's why it was chosen for satellite/cable broadcast and subsequently for HLS.

**Q: What's the trade-off of segment duration?**
> Shorter segments (2sec): Lower latency, better seek accuracy, but more HTTP requests and more playlist overhead.  
> Longer segments (30sec): Fewer requests, better for high-latency connections, but coarser seek granularity and higher startup delay.  
> 10 seconds is the industry sweet spot for VOD.

**Q: Could you use DASH instead of HLS?**
> Yes. MPEG-DASH (Dynamic Adaptive Streaming over HTTP) is the ISO standard alternative. It uses `.mpd` manifests instead of `.m3u8`. HLS was chosen because of better native browser support (especially Safari on iOS/macOS which requires HLS). For cross-platform production, both could be generated.

**Q: What FFmpeg flags would you change for production?**
> For production I'd add:
> - `-c:v libx264 -crf 23 -preset fast` — re-encode to H.264 if the source codec varies
> - Multiple bitrate renditions (`-map 0:v -map 0:a` for each resolution) for adaptive bitrate
> - `-hls_segment_type fmp4` for better browser compatibility in some scenarios
> - Run FFmpeg in a background thread/task queue (not synchronously blocking the HTTP thread)
