# VOD Segmentation Scripts

This directory contains scripts to convert MP4 video files into HLS (HTTP Live Streaming) format for video streaming applications.

## Overview

The segmentation scripts use FFmpeg to convert standard MP4 files into HLS streaming format, creating:
- `.m3u8` playlist files
- `.ts` video segment files
- Proper streaming metadata

## Files

- `segment_video.bat` - Windows batch script
- `segment_video.sh` - Unix/Linux/Mac shell script
- `videos/output/` - Output directory for generated HLS files

## Prerequisites

### FFmpeg Installation

**Windows:**
1. Download FFmpeg from: https://ffmpeg.org/download.html#build-windows
2. Extract to a folder (e.g., `C:\ffmpeg`)
3. Add FFmpeg to your system PATH environment variable

**Ubuntu/Debian:**
```bash
sudo apt install ffmpeg
```

**CentOS/RHEL:**
```bash
sudo yum install ffmpeg
# or for newer versions:
sudo dnf install ffmpeg
```

**macOS:**
```bash
brew install ffmpeg
```

## Usage

### Basic Usage

**Windows:**
```batch
segment_video.bat myvideo.mp4
```

**Unix/Linux/Mac:**
```bash
./segment_video.sh myvideo.mp4
```

### Advanced Usage

Specify custom segment duration (in seconds):
```bash
# Windows
segment_video.bat myvideo.mp4 15

# Unix/Linux/Mac
./segment_video.sh myvideo.mp4 15
```

Specify custom output name:
```bash
# Windows
segment_video.bat myvideo.mp4 10 custom_name

# Unix/Linux/Mac
./segment_video.sh myvideo.mp4 10 custom_name
```

## Parameters

- `input_video.mp4` - Path to input MP4 file (required)
- `segment_duration` - Duration of each segment in seconds (default: 10)
- `output_name` - Name for output files (default: "playlist")

## Output

The scripts create HLS files in the `videos/output/` directory:
- `playlist.m3u8` (or custom name)
- `playlist0.ts`, `playlist1.ts`, etc. (video segments)

## Integration with Video Metadata Service

After running the segmentation script:

1. Copy the generated `.m3u8` file path
2. Use this path in your Video entity's `hlsPath` field when creating video records
3. Example path: `videos/output/playlist.m3u8`

## FFmpeg Command Details

The scripts use this FFmpeg command:
```bash
ffmpeg -i input_video.mp4 \
    -c:v copy \
    -c:a copy \
    -start_number 0 \
    -hls_time 10 \
    -hls_list_size 0 \
    -f hls output/playlist.m3u8
```

**Parameters explained:**
- `-c:v copy` - Copy video codec (no re-encoding)
- `-c:a copy` - Copy audio codec (no re-encoding)
- `-start_number 0` - Start segment numbering at 0
- `-hls_time 10` - Create 10-second segments
- `-hls_list_size 0` - Include all segments in playlist
- `-f hls` - Output HLS format

## Troubleshooting

### Common Issues

1. **FFmpeg not found:**
   - Ensure FFmpeg is installed and available in PATH
   - Test with: `ffmpeg -version`

2. **Permission denied (Unix/Linux/Mac):**
   ```bash
   chmod +x segment_video.sh
   ```

3. **Input file not found:**
   - Check file path and permissions
   - Ensure file is a valid MP4

4. **Conversion fails:**
   - Check available disk space
   - Verify input file integrity
   - Try with a smaller test file

## Performance Tips

- For faster processing, use `-c:v copy -c:a copy` (no re-encoding)
- For better compression, use `-c:v libx264 -c:a aac` (requires re-encoding)
- Adjust segment duration based on your needs (shorter = more segments)
- Ensure sufficient disk space for output files

## Security Note

These scripts assume FFmpeg is properly installed and configured. Always download FFmpeg from official sources and keep it updated.