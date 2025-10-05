**StreamingBug: Upload Failure and File Lock Issue**

### **Abstract**

During the Week 2 development phase of the *StreamLite* project, a critical issue occurred in the `StreamingController` during the video upload process. The system consistently failed to handle file uploads properly, resulting in exceptions such as *FileNotFoundException* and *FileUploadException*. This report documents the root cause, analysis, and final resolution — contributing to improved reliability in file-handling operations for the StreamLite streaming pipeline.

---

### **1. Problem Description**

When attempting to upload a video file through the `/upload` endpoint, the application threw one of the following errors:

* **Phase 1 Error:**

  ```
  java.io.FileNotFoundException: ...\videos\input\videoplayback.mp4 (The system cannot find the path specified)
  ```
* **Phase 2 Error:**

  ```
  org.apache.tomcat.util.http.fileupload.FileUploadException: Cannot write uploaded file to disk!
  ```
* **Phase 3 Error (after partial fix):**

  ```
  The process cannot access the file because it is being used by another process
  ```

These errors collectively indicated failures during file transfer and locking while processing multipart uploads.

---

### **2. Root Cause Analysis**

#### (a) **Initial Write Path Issue**

The file upload path was being resolved inside Tomcat’s temporary directory:

```
C:\Users\<user>\AppData\Local\Temp\tomcat.*
```

Spring Boot uses Tomcat’s internal temp directory for intermediate file writes during `MultipartFile.transferTo()`.
However, Tomcat lacked sufficient permission or pre-created folders, leading to **FileNotFoundException**.

#### (b) **Permission and Temp Directory Conflict**

The `FileUploadException` occurred because Tomcat could not write to its default temp directory due to permission and path resolution conflicts when running inside Spring Boot’s embedded container.

#### (c) **File Locking (Windows Specific)**

Even after switching to `Files.copy()`, Windows held a temporary file lock for a short time after writing.
When the FFmpeg segmentation process (`segment_video.bat`) attempted to read the same file immediately after writing, it failed with the “file in use” error.

---

### **3. Solution Implemented**

#### ✅ Step 1 — Redirect Temporary Storage

Configured file uploads to store in a local, project-managed folder:

```properties
spring.servlet.multipart.location=${user.dir}/videos/tmp
```

This moved the temporary upload space from Tomcat’s volatile temp directory to the StreamLite project directory.

#### ✅ Step 2 — Switched from `transferTo()` to `Files.copy()`

Replaced:

```java
file.transferTo(dest);
```

with:

```java
Files.copy(file.getInputStream(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
```

This bypassed Tomcat’s intermediate file handling and directly wrote to the intended location.

#### ✅ Step 3 — Added Controlled Delay and Stream Safety

Inserted:

```java
Thread.sleep(300);
```

after closing the file stream, ensuring that Windows fully released the file handle before invoking the segmentation process.

#### ✅ Step 4 — Improved Logging and Error Traceability

Added:

```java
System.out.println("Running segmentation: segment_video.bat " + dest.getAbsolutePath());
processBuilder.inheritIO();
```

to display FFmpeg’s runtime logs directly in the console for debugging and verification.

---

### **4. Outcome**

After implementing the above fixes:

* File uploads now save reliably to `videos/input/`.
* Segmentation scripts execute immediately after upload.
* No permission or file lock issues remain.
* The solution is OS-agnostic, tested successfully on Windows 10 with Spring Boot 3.x.

---

### **5. Key Learnings**

1. **Avoid Tomcat temp dependencies** — explicitly control your upload directories.
2. **Always ensure file handle release** — even `try-with-resources` may require short sleep on Windows systems.
3. **Use `Files.copy()`** over `transferTo()` for portable, stable file operations.
4. **Debug visibility** via inherited process I/O can drastically speed up troubleshooting.

---

### **6. Future Implications**

This incident highlights the importance of **OS-level file system behavior awareness** in cloud streaming architectures.
For production deployment, future versions of StreamLite will:

* Move to an asynchronous file queue for segmentation.
* Use distributed storage (e.g., MinIO or AWS S3) to avoid local file locks.
* Integrate FFmpeg processing as a background microservice.

---

**Report Prepared by:**
*Lakshya Bhardwaj*
StreamLite Project — Week 2 Debug Log
**Title:** *StreamingBug: File Upload and Lock Resolution in Java Spring Video Pipeline*

---