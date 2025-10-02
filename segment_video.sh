#!/bin/bash

# VOD Segmentation Script for Unix/Linux/Mac
# This script converts MP4 files to HLS streaming format using FFmpeg

# Function to display usage information
usage() {
    echo "Usage: $0 input_video.mp4 [segment_duration] [output_name]"
    echo
    echo "Parameters:"
    echo "  input_video.mp4    - Path to input MP4 file"
    echo "  segment_duration   - Duration of each segment in seconds (default: 10)"
    echo "  output_name        - Name for output files (default: playlist)"
    echo
    echo "Examples:"
    echo "  $0 myvideo.mp4"
    echo "  $0 myvideo.mp4 15"
    echo "  $0 myvideo.mp4 10 custom_name"
    echo
    exit 1
}

# Check if input file is provided
if [ $# -eq 0 ]; then
    usage
fi

input_file="$1"
segment_duration="${2:-10}"
output_name="${3:-playlist}"

# Check if input file exists
if [ ! -f "$input_file" ]; then
    echo "Error: Input file '$input_file' not found!"
    exit 1
fi

# Check if FFmpeg is available
if ! command -v ffmpeg &> /dev/null; then
    echo "Error: FFmpeg not found! Please ensure FFmpeg is installed and available in PATH."
    echo "You can install FFmpeg using your package manager:"
    echo "  Ubuntu/Debian: sudo apt install ffmpeg"
    echo "  CentOS/RHEL: sudo yum install ffmpeg"
    echo "  macOS: brew install ffmpeg"
    exit 1
fi

# Create output directory if it doesn't exist
mkdir -p videos/output

echo
echo "========================================"
echo "VOD Segmentation Script"
echo "========================================"
echo "Input file: $input_file"
echo "Segment duration: $segment_duration seconds"
echo "Output name: $output_name"
echo "Output directory: videos/output/"
echo "========================================"
echo

# Create output filename
output_playlist="videos/output/$output_name.m3u8"

echo "Converting video to HLS format..."
echo "This may take some time depending on video length and size."
echo

# Run FFmpeg command
ffmpeg -i "$input_file" \
    -c:v copy \
    -c:a copy \
    -start_number 0 \
    -hls_time "$segment_duration" \
    -hls_list_size 0 \
    -f hls \
    "$output_playlist"

# Check if conversion was successful
if [ $? -eq 0 ]; then
    echo
    echo "========================================"
    echo "Conversion completed successfully!"
    echo "========================================"
    echo
    echo "Output files created in: videos/output/"
    echo "Playlist file: $output_name.m3u8"
    echo
    echo "You can now use this playlist file path in your Video entity:"
    echo "$(pwd)/$output_playlist"
    echo
    echo "Relative path for database: videos/output/$output_name.m3u8"
    echo "========================================"
    echo
else
    echo
    echo "Error: FFmpeg conversion failed!"
    exit 1
fi