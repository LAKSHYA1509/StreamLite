# 🗄️ Database Layer — JPA, Hibernate & PostgreSQL Deep Dive

> Spring Data JPA, Entity design, Repository pattern, and everything a senior engineer will ask about the data layer.

---

## 1. Technology Stack

| Layer | Technology |
|---|---|
| ORM | Hibernate (via Spring Data JPA) |
| Database | PostgreSQL 15+ |
| JPA Provider | Hibernate 6.x (included in Spring Boot 3.x) |
| Repository Abstraction | Spring Data JPA (`JpaRepository`) |
| Migrations | `ddl-auto=update` (auto schema management) |
| Connection | PostgreSQL JDBC Driver |

---

## 2. The Entities

### 2.1 User Entity — Full Analysis

```java
@Entity(name = "user")          // JPA entity name (used in JPQL)
@Table(name = "users")          // Actual database table name
@Getter @Setter                 // Lombok: generates getters and setters
@AllArgsConstructor             // Lombok: all-args constructor
@NoArgsConstructor              // Lombok: no-args constructor (required by JPA)
public class User implements UserDetails {

    @Id                         // Primary key
    private String userId;      // UUID string (not auto-generated — we generate it)

    @Column(name = "user_name", nullable = false)
    private String name;

    @Column(unique = true, nullable = false)   // Unique + NOT NULL constraint
    private String email;

    @Getter(AccessLevel.NONE)   // Override Lombok's default getter (use explicit one below)
    private String password;

    @Column(length = 1000)      // VARCHAR(1000) not default VARCHAR(255)
    private String about;

    @Column(length = 1000)
    private String profilePic;

    private String phoneNumber;

    @Column(unique = true)      // Unique constraint — no two users can have same stream key
    private String streamKey;

    @Getter(value = AccessLevel.NONE)  // Override Lombok — use isEnabled() method
    private boolean enabled = true;    // Default: enabled

    private boolean emailVerified = false;
    private boolean phoneVerified = false;

    @Enumerated(value = EnumType.STRING)
    // Stores enum as "SELF", "GOOGLE", etc. instead of ordinal (0, 1, 2...)
    private Providers provider = Providers.SELF;
    
    private String providerUserId;     // The sub/ID from OAuth provider

    @ElementCollection(fetch = FetchType.EAGER)
    // Creates a separate join table: user_role_list(user_user_id, role_list)
    // EAGER: loaded immediately with the User (not lazily)
    private List<String> roleList = new ArrayList<>();

    private String emailToken;         // For email verification tokens
}
```

**Generated SQL schema:**
```sql
CREATE TABLE users (
    user_id         VARCHAR(255) PRIMARY KEY,
    user_name       VARCHAR(255) NOT NULL,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password        VARCHAR(255),
    about           VARCHAR(1000),
    profile_pic     VARCHAR(1000),
    phone_number    VARCHAR(255),
    stream_key      VARCHAR(255) UNIQUE,
    enabled         BOOLEAN DEFAULT TRUE,
    email_verified  BOOLEAN DEFAULT FALSE,
    phone_verified  BOOLEAN DEFAULT FALSE,
    provider        VARCHAR(255) DEFAULT 'SELF',
    provider_user_id VARCHAR(255),
    email_token     VARCHAR(255)
);

CREATE TABLE user_role_list (
    user_user_id    VARCHAR(255) REFERENCES users(user_id),
    role_list       VARCHAR(255)
);
```

### 2.2 Video Entity — Full Analysis

```java
@Entity
@Data       // Lombok: @Getter + @Setter + @ToString + @EqualsAndHashCode + @RequiredArgsConstructor
@Table(name = "videos")
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)  
    // Hibernate generates a UUID automatically using UUIDGenerator
    private String id;

    @NotBlank(message = "Title is required")        // Bean Validation — can't be blank
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @Size(max = 1000, message = "Description must not exceed 1000 characters")
    private String description;

    @NotBlank(message = "Uploader ID is required")
    private String uploaderId;      // Could be email or UUID of the user

    private String uploadDate;      // Could be LocalDateTime — currently String

    @Size(max = 500, message = "HLS path must not exceed 500 characters")
    private String hlsPath;         // e.g., "videos/output/playlist.m3u8"
}
```

**Generated SQL schema:**
```sql
CREATE TABLE videos (
    id              VARCHAR(255) PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    description     VARCHAR(1000),
    uploader_id     VARCHAR(255) NOT NULL,
    upload_date     VARCHAR(255),        -- String, not TIMESTAMP
    hls_path        VARCHAR(500)
);
```

---

## 3. Repositories

### UserRepo.java

```java
@Repository
public interface UserRepo extends JpaRepository<User, String> {
    // JpaRepository<Entity, PrimaryKeyType> provides:
    // - save(entity), findById(id), findAll(), deleteById(id)
    // - Paging: findAll(Pageable)
    // - Count: count()
    
    // Custom queries — Spring Data derives SQL from method name:
    Optional<User> findByEmail(String email);
    // → SELECT * FROM users WHERE email = ?
    
    Optional<User> findByEmailAndPassword(String email, String password);
    // → SELECT * FROM users WHERE email = ? AND password = ?
    // NOTE: This is rarely used directly — Spring Security handles the comparison
}
```

### VideoRepo.java

```java
@Repository
public interface VideoRepo extends JpaRepository<Video, String> {
    // Inherits all JpaRepository methods
    // No custom queries needed for basic use
    // Could add: List<Video> findByUploaderId(String uploaderId);
}
```

### How Spring Data JPA Derives Queries

Spring Data JPA parses method names and generates JPQL/SQL automatically:

| Method Name | Generated Query |
|---|---|
| `findByEmail(String email)` | `WHERE email = ?` |
| `findByEmailAndPassword(...)` | `WHERE email = ? AND password = ?` |
| `findByUploaderId(String id)` | `WHERE uploader_id = ?` |
| `findByTitleContaining(String s)` | `WHERE title LIKE %?%` |
| `findAllByOrderByUploadDateDesc()` | `ORDER BY upload_date DESC` |

---

## 4. Hibernate DDL Auto — `ddl-auto=update`

```properties
spring.jpa.hibernate.ddl-auto=update
```

**What it does:** On startup, Hibernate compares the entity classes to the existing database schema and applies **only new changes** (new columns, new tables). It never drops existing columns or tables.

| Setting | Behavior | Use Case |
|---|---|---|
| `create` | Drop and recreate on startup | Development (fresh start) |
| `create-drop` | Create on startup, drop on shutdown | Testing |
| `update` | Add new columns/tables only | Development, Staging |
| `validate` | Validate but don't change | Production (often paired with Flyway/Liquibase) |
| `none` | Do nothing | Production with migration tools |

**⚠️ Production warning:** Never use `update` in production. Use **Flyway** or **Liquibase** for controlled, versioned migrations.

---

## 5. JPA vs Hibernate vs Spring Data JPA

```
Spring Data JPA  ← StreamLite uses this (highest level)
     │ extends
Hibernate JPA (ORM)  ← Spring Data JPA's persistence provider
     │ implements
JPA Specification  ← Java standard API (javax.persistence / jakarta.persistence)
```

- **JPA** = Specification (interface only, like JDBC)
- **Hibernate** = JPA implementation (like MySQL JDBC Driver)
- **Spring Data JPA** = Repository abstraction on top of Hibernate (like Spring JDBC Template)

---

## 6. @GeneratedValue Strategies

In the `Video` entity:
```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private String id;
```

**GenerationType options:**

| Strategy | Mechanism | When to use |
|---|---|---|
| `AUTO` | Let Hibernate decide (sequence, table, or identity) | Default |
| `IDENTITY` | Database auto-increment (MySQL: `id INT AUTO_INCREMENT`) | Simple numeric IDs |
| `SEQUENCE` | Database sequence object (PostgreSQL native) | Batch inserts, better performance |
| `TABLE` | Separate table manages ID generation | Portable but slow |
| `UUID` | Hibernate generates a UUID | Distributed systems, no collision |

UUID is used for `Video.id` because: (1) Videos can be created on multiple servers without collision, (2) IDs are not guessable/sequential (security), (3) Works without a database round-trip.

The `User.userId` is **manually set** (`user.setUserId(UUID.randomUUID().toString())`) — not using `@GeneratedValue`. This is equivalent but means `userId` must always be set before saving.

---

## 7. Lombok Annotations Used

| Annotation | Purpose |
|---|---|
| `@Getter` | Generate `getXxx()` for all fields |
| `@Setter` | Generate `setXxx(value)` for all fields |
| `@AllArgsConstructor` | Constructor with all fields |
| `@NoArgsConstructor` | No-args constructor (required by JPA/Hibernate) |
| `@Builder` (in Message.java) | Fluent builder pattern |
| `@Data` | `@Getter + @Setter + @ToString + @EqualsAndHashCode + @RequiredArgsConstructor` |
| `@Getter(AccessLevel.NONE)` | Suppress Lombok getter for a specific field (use custom getter instead) |

**Why JPA requires `@NoArgsConstructor`:**
Hibernate uses reflection to create instances during hydration (loading from DB). It calls the no-args constructor first, then sets fields via reflection. Without it, Hibernate throws a `NoSuchMethodException`.

---

## 8. The UserService Layer

### Service Interface

```java
public interface UserService {
    User saveUser(User user);
    Optional<User> getUserById(String id);
    Optional<User> updateUser(User user);
    void deleteUser(String id);
    boolean isUserExist(String userId);
    boolean isUserExistByEmail(String email);
    List<User> getAllUsers();
    User getUserByEmail(String email);
}
```

### UserServiceimpl — Key Method Analysis

```java
@Override
public User saveUser(User user) {
    // 1. Generate UUID for the user
    user.setUserId(UUID.randomUUID().toString());
    
    // 2. Hash the password — NEVER store plaintext!
    user.setPassword(passwordEncoder.encode(user.getPassword()));
    
    // 3. Assign default role
    user.setRoleList(List.of(AppConstants.ROLE_USER));
    
    // 4. Log the provider (SELF, GOOGLE, etc.)
    logger.info(user.getProvider().toString());
    
    // 5. Persist to DB
    return userRepo.save(user);
}

@Override
public Optional<User> updateUser(User user) {
    // 1. Verify user exists (throws ResourceNotFoundException if not)
    User existingUser = userRepo.findById(user.getUserId())
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    
    // 2. Update all mutable fields
    existingUser.setName(user.getName());
    existingUser.setEmail(user.getEmail());
    existingUser.setPassword(user.getPassword());  // Caller should already encode if changing
    existingUser.setAbout(user.getAbout());
    existingUser.setPhoneNumber(user.getPhoneNumber());
    existingUser.setProfilePic(user.getProfilePic());
    existingUser.setEnabled(user.isEnabled());
    existingUser.setEmailVerified(user.isEmailVerified());
    existingUser.setPhoneVerified(user.isPhoneVerified());
    existingUser.setProvider(user.getProvider());
    existingUser.setProviderUserId(user.getProviderUserId());
    existingUser.setStreamKey(user.getStreamKey());  // Critical for stream key update
    
    // 3. Save
    User saved = userRepo.save(existingUser);
    return Optional.ofNullable(saved);
}
```

---

## 9. Video CRUD REST API — VideoController

```java
@RestController
@RequestMapping("/api/videos")
@CrossOrigin(origins = "*")      // Allow all cross-origin requests
public class VideoController {

    // DTOs (Data Transfer Objects) — inner classes
    // Prevents exposing JPA entity directly (good practice!)
    
    public static class VideoRequest {
        private String title, description, hlsPath;
        // Only the fields a client can set (not id, uploaderId, uploadDate)
    }
    
    public static class VideoResponse {
        // Controls exactly what gets serialized to JSON
        // Prevents accidentally exposing internal fields
        public VideoResponse(Video video) { ... }
    }

    // POST /api/videos — Create video
    @PostMapping
    @PreAuthorize("hasRole('USER')")  // Method-level security
    public ResponseEntity<?> createVideo(@Valid @RequestBody VideoRequest request) {
        // @Valid triggers Bean Validation on Video's @NotBlank, @Size constraints
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String uploaderId = auth.getName();  // email of logged-in user
        // ...
    }

    // PUT /api/videos/{id} — Update video
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<?> updateVideo(@PathVariable String id, @RequestBody VideoRequest request) {
        // Authorization check: only the owner can update
        if (!video.getUploaderId().equals(currentUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "You can only update your own videos"));
        }
        // ...
    }
}
```

**Key concepts:**

`@PreAuthorize("hasRole('USER')")` — **Method-level security**. Even if an endpoint isn't in the `SecurityFilterChain` rules, this annotation adds an extra check. Spring AOP intercepts the method call and verifies the principal's authorities.

`@CrossOrigin(origins = "*")` — Adds `Access-Control-Allow-Origin: *` header to all responses from this controller, allowing JavaScript from any domain to call this API.

**Why use DTOs instead of returning the entity directly?**
1. **Security:** Avoid exposing sensitive fields (password hash, emailToken)
2. **Versioning:** API contract is decoupled from internal entity changes
3. **Control:** Choose exactly what gets serialized

---

## 10. Interview Questions on JPA/Hibernate — Prepared Answers

**Q: What is the difference between JPA and Hibernate?**
> JPA (Jakarta Persistence API) is an **interface/specification** — it defines annotations like `@Entity`, `@Id`, `@OneToMany`, etc. Hibernate is a **concrete implementation** of JPA. Spring Data JPA provides a higher-level **repository abstraction** on top of Hibernate. You could swap Hibernate for EclipseLink without changing JPA code.

**Q: Explain FetchType.EAGER vs LAZY**
> `EAGER` = load related data immediately when the parent is loaded.  
> `LAZY` = load related data only when you access it (proxy).  
> `@ElementCollection(fetch = FetchType.EAGER)` on `roleList` means roles are loaded with the user — needed because Spring Security checks `getAuthorities()` outside the JPA session. With LAZY, this would throw `LazyInitializationException`.

**Q: What is N+1 query problem?**
> If you load a list of 100 videos and each has a lazy-loaded `User` relationship, accessing `video.getUser()` in a loop fires 100 additional queries (1 for all videos + N=100 for users). Solution: use `@EntityGraph`, `JOIN FETCH` in JPQL, or `FetchType.EAGER` selectively.

**Q: What does `@GeneratedValue(strategy = GenerationType.UUID)` do?**
> Hibernate automatically generates a UUID (Universally Unique Identifier) for the primary key before inserting. The UUID is a 128-bit random value in format `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`, virtually guaranteed to be unique without a database round-trip.

**Q: What is the difference between `save()` and `saveAndFlush()` in JpaRepository?**
> `save()` merges the entity into the persistence context — the SQL may be executed later (when the transaction commits). `saveAndFlush()` immediately synchronizes the persistence context with the database (executes the SQL right away). Use `saveAndFlush()` when you need the data in the DB immediately (e.g., before calling a stored procedure).

**Q: What is optimistic locking?**
> A concurrency strategy where you assume conflicts are rare. You add a `@Version` field to entities. When updating, Hibernate includes `WHERE version = ?` in the UPDATE statement. If another transaction already incremented the version, the update affects 0 rows → `OptimisticLockException`. Video entity could benefit from this to prevent concurrent edits.
