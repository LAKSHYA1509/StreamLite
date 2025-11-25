@echo off
echo.
echo =========================================
echo      StreamLite Live Stream Manager
echo =========================================
echo.

REM Define the directory where HLS files are stored
set "HLS_DIR=C:\nginx-rtmp\nginx-rtmp-win32-1.2.1\html\hls"

echo [*] Cleaning up previous stream files from:
echo     %HLS_DIR%
echo.

REM Delete all old .ts video chunks and the old playlist file
REM The /Q makes it run quietly without asking for confirmation
del /Q "%HLS_DIR%\*.ts"
del /Q "%HLS_DIR%\live.m3u8"

echo [+] Cleanup complete.
echo [+] Starting FFmpeg. Waiting for stream from OBS...
echo.

REM Run the FFmpeg command (this is the same as before)
ffmpeg -fflags +genpts -i rtmp://localhost/live/my_stream_key -c:v libx264 -c:a aac -af aresample=async=1 -f hls -hls_time 2 -hls_playlist_type event "%HLS_DIR%\live.m3u8"

echo.
echo [!] FFmpeg has stopped.
pause