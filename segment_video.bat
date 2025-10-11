@echo off
setlocal enabledelayedexpansion
REM Simple VOD Segmentation Script for Windows

if "%~1"=="" goto :usage

set "input_file=%~1"
set "duration=%~2"
set "output_name=%~3"

if "%duration%"=="" set "duration=10"
if "%output_name%"=="" set "output_name=playlist"

echo ========================================
echo VOD Segmentation Script
echo ========================================
echo Input: %input_file%
echo Duration: %duration% seconds
echo Output: %output_name%
echo ========================================

if not exist "%input_file%" (
    echo ERROR: File '%input_file%' not found!
    echo.
    echo Available MP4 files:
    dir *.mp4
    goto :error
)

ffmpeg -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: FFmpeg not found!
    echo Please install FFmpeg from https://ffmpeg.org/download.html
    goto :error
)

REM Create output directory
if not exist "videos\output" mkdir "videos\output"

echo.
echo Converting to HLS format...
echo.

ffmpeg -i "%input_file%" -c:v copy -c:a copy -start_number 0 -hls_time %duration% -hls_list_size 0 -f hls "videos\output\%output_name%.m3u8"

if errorlevel 1 (
    echo.
    echo ERROR: Conversion failed!
    goto :error
)

echo.
echo SUCCESS! Files created in videos\output\
echo Playlist: %output_name%.m3u8
echo.
echo Database path: videos/output/%output_name%.m3u8
echo.
echo Checking created files:
dir videos\output
goto :end

:usage
echo Usage: segment_video.bat input.mp4 [duration] [name]
echo Example: segment_video.bat "video.mp4" 20 playlist
goto :end

:error
echo.
pause

:end
endlocal
