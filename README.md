# StreamLite: Week 1

# ✅ Week 1: Foundation & Core Services (VOD)

🎯 Goal: Get a working **backend with Auth + Video Metadata + HLS segmentation script** running in Docker.

---

### 1. Environment Setup

* [ ] **Create GitHub repo** `streamlite`
* [ ] **Initialize project structure:**

  ```
  streamlite/
   ├── backend/   (Spring Boot)
   ├── frontend/  (React, later)
   ├── docker/    (compose files, configs)
   ├── scripts/   (ffmpeg helpers)
   └── docs/      (research notes, diagrams)
  ```
* [ ] Install **Docker & Docker Compose**
* [ ] Create `docker-compose.yml` with:

  * PostgreSQL (for metadata, users)
  * Redis (for caching)
* [ ] Verify with `docker-compose up -d` → containers running

---

### 2. Backend Skeleton (Spring Boot)

* [ ] Create **Spring Boot project** (Maven/Gradle, Java 17, Spring Boot 3.x)
* [ ] Add dependencies:

  * Spring Web (REST APIs)
  * Spring Data JPA (Postgres)
  * Spring Security (JWT auth)
  * Redis Starter
* [ ] Create application structure:

  ```
  backend/src/main/java/com/streamlite/
   ├── auth/      (JWT security)
   ├── video/     (metadata APIs)
   ├── config/    (DB, Redis, security configs)
   └── common/
  ```
* [ ] Configure `application.yml` for Postgres + Redis connection
* [ ] Test backend runs with `./mvnw spring-boot:run`

---

### 3. User/Auth Service

* [ ] Create **User entity** (id, username, password-hash, email, roles)
* [ ] Add **UserRepository** (JPA)
* [ ] Add **AuthController**:

  * POST `/register` (save user)
  * POST `/login` (return JWT)
* [ ] Implement **JWT filter** to secure APIs
* [ ] Test with Postman:

  * Register user
  * Login → get JWT
  * Call protected API with `Authorization: Bearer <token>`

---

### 4. Video Metadata Service

* [ ] Create **Video entity** (id, title, description, creatorId, filepath, createdAt)
* [ ] Add **VideoRepository** (JPA)
* [ ] Create **VideoController**:

  * POST `/videos` → add video metadata
  * GET `/videos/{id}` → get metadata
  * GET `/videos` → list all videos
* [ ] Secure with JWT (only authenticated users can upload metadata)

---

### 5. FFmpeg VOD Segmentation

* [ ] Install FFmpeg locally (`sudo apt install ffmpeg` or `brew install ffmpeg`)
* [ ] Create script `scripts/segment.sh`:

  ```bash
  #!/bin/bash
  INPUT=$1
  OUTPUT_DIR=$2
  mkdir -p $OUTPUT_DIR
  ffmpeg -i "$INPUT" -codec: copy -start_number 0 -hls_time 4 -hls_list_size 0 -f hls "$OUTPUT_DIR/index.m3u8"
  ```
* [ ] Test with:

  ```
  ./scripts/segment.sh sample.mp4 ./output
  ```
* [ ] Verify generated files:

  * `index.m3u8` (playlist)
  * `0.ts, 1.ts, 2.ts...` (segments)

---

### 6. Integration & Validation

* [ ] Store segmented file path in **Video metadata DB**
* [ ] Expose API `/videos/{id}/manifest` to return path to `.m3u8`
* [ ] Test flow:

  1. Register user
  2. Upload metadata for `sample.mp4`
  3. Run `segment.sh`
  4. Call `/videos/{id}/manifest` → get `.m3u8` path
  5. Open in VLC → verify video plays

---

### 📊 Deliverables for Week 1

* [ ] GitHub repo with backend + Docker setup + script
* [ ] Postman collection (Auth + Video APIs working)
* [ ] One segmented video playable via `.m3u8`
* [ ] Document steps in `docs/week1_notes.md`

---

👉 By the end of Week 1, you’ll **have VOD working with user login and metadata**, which already looks like a mini OTT backend.

Do you want me to also **draft the `docker-compose.yml` + Spring Boot starter code (pom.xml + Auth skeleton)** so you can literally start coding tonight?
