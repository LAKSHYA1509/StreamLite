# 🔐 Security & Authentication — Deep Dive

> Spring Security 6, BCrypt, OAuth2 (Google + GitHub), UserDetails — everything an interviewer can ask.

---

## 1. Spring Security Architecture — How It Works

Spring Security is a **filter-chain based** security framework. Every HTTP request passes through a series of filters **before** reaching your controller.

```
HTTP Request
    │
    ▼
[SecurityFilterChain]
    ├─ UsernamePasswordAuthenticationFilter  ← handles /authenticate POST
    ├─ OAuth2LoginAuthenticationFilter       ← handles /oauth2/authorization/**
    ├─ BasicAuthenticationFilter
    ├─ ExceptionTranslationFilter            ← converts exceptions to 401/403
    ├─ FilterSecurityInterceptor             ← checks URL authorization rules
    └─ ... (20+ filters in total)
    │
    ▼
[Your Controller]
```

---

## 2. SecurityConfig.java — Complete Code Analysis

```java
@Configuration
public class SecurityConfig {

    @Autowired
    private SecurityCustomUserDetailService userDetailService;

    @Autowired
    private OAuthAuthenticationSuccessHandler handler;

    // =========================================================
    // 1. DaoAuthenticationProvider — connects UserDetailsService to Spring Security
    // =========================================================
    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider();
        daoAuthenticationProvider.setUserDetailsService(userDetailService);
        daoAuthenticationProvider.setPasswordEncoder(passwordEncoder());
        return daoAuthenticationProvider;
    }

    // =========================================================
    // 2. BCryptPasswordEncoder — THE standard for password hashing
    // =========================================================
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // =========================================================
    // 3. SecurityFilterChain — the heart of all security rules
    // =========================================================
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity httpSecurity) throws Exception {

        // URL-level authorization
        httpSecurity.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers("/user/**").authenticated(); // ALL /user/** requires login
            authorize.anyRequest().permitAll();                    // Everything else is public
        });

        // Form login configuration
        httpSecurity.formLogin(formLogin -> {
            formLogin.loginPage("/login");                  // Custom login page
            formLogin.loginProcessingUrl("/authenticate");  // POST URL Spring processes
            formLogin.successForwardUrl("/user/profile");   // After login → profile page
            formLogin.usernameParameter("email");           // Field name in form
            formLogin.passwordParameter("password");        // Field name in form
        });

        // CSRF: disable globally (needed for REST API calls like /upload, /generate-stream-key)
        httpSecurity.csrf(AbstractHttpConfigurer::disable);

        // Logout
        httpSecurity.logout(logoutForm -> {
            logoutForm.logoutUrl("/do-logout");
            logoutForm.logoutSuccessUrl("/login?logout=true");
        });

        // OAuth2 Login
        httpSecurity.oauth2Login(oauth -> {
            oauth.loginPage("/login");            // Use same login page (shows Google/GitHub buttons)
            oauth.successHandler(handler);        // Custom handler after OAuth2 success
        });

        httpSecurity.cors(withDefaults());  // Enable CORS
        return httpSecurity.build();
    }
}
```

---

## 3. BCrypt Password Hashing — Internals

### What BCrypt Does

BCrypt is a **password-hashing function** based on the Blowfish cipher.

```
BCrypt.hash("mypassword123", 10)
    → "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
         │    │   │
         │    │   └─ Salt (22 chars, random)
         │    └─ Cost factor / work factor (10 = 2^10 = 1024 rounds)
         └─ BCrypt version identifier
```

The output is **always different** because of the random salt — even if two users have the same password.

### Why Not MD5 or SHA-256?

| Algorithm | Salt | Adaptive | GPU Cracking | Status |
|---|---|---|---|---|
| MD5 | No | No | Billions/sec | **Broken** |
| SHA-256 | No | No | Millions/sec | **Not for passwords** |
| BCrypt | Yes (built-in) | Yes (cost factor) | ~1000/sec | **Industry Standard** |
| Argon2id | Yes | Yes | ~100/sec | **Best (future)** |

### BCrypt in Spring Security Flow

```java
// When user registers:
user.setPassword(passwordEncoder.encode(user.getPassword()));
// Becomes: $2a$10$xyz... (stored in DB)

// When user logs in:
// Spring Security calls:
passwordEncoder.matches("rawPassword", "$2a$10$xyz...")
// → true/false
// DaoAuthenticationProvider handles this automatically
```

---

## 4. UserDetails Interface — The Bridge Between Your Entity and Spring Security

The `User` entity implements `UserDetails` — this is how Spring Security knows about your users.

```java
@Entity(name = "user")
@Table(name = "users")
public class User implements UserDetails {

    @Id
    private String userId;
    
    @Column(unique = true, nullable = false)
    private String email;          // Used as username in Spring Security
    
    private String password;       // BCrypt hashed
    
    @Column(unique = true)
    private String streamKey;      // UUID for RTMP live streaming
    
    private boolean enabled = true;
    private boolean emailVerified = false;
    
    @Enumerated(value = EnumType.STRING)
    private Providers provider = Providers.SELF;  // SELF, GOOGLE, GITHUB, LINKEDIN
    
    @ElementCollection(fetch = FetchType.EAGER)
    private List<String> roleList = new ArrayList<>();  // ["ROLE_USER"]

    // ===== UserDetails interface methods =====
    
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Converts ["ROLE_USER"] → [SimpleGrantedAuthority("ROLE_USER")]
        return roleList.stream()
            .map(role -> new SimpleGrantedAuthority(role))
            .collect(Collectors.toList());
    }

    @Override
    public String getUsername() {
        return this.email;  // Email IS the username
    }
    
    @Override
    public boolean isEnabled() { return this.enabled; }
    
    // isAccountNonExpired, isAccountNonLocked, isCredentialsNonExpired all return true
    // (could be false to implement account locking logic)
}
```

### @ElementCollection for Roles

`roleList` is stored in a separate table:
```sql
-- Spring JPA creates this automatically:
CREATE TABLE user_role_list (
    user_user_id VARCHAR(255),
    role_list VARCHAR(255)
);
-- Example row: ('abc-uuid-123', 'ROLE_USER')
```

This allows multiple roles per user without a separate `Role` entity.

---

## 5. SecurityCustomUserDetailService — The UserDetailsService

```java
@Service
public class SecurityCustomUserDetailService implements UserDetailsService {

    @Autowired
    private UserRepo userRepo;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // username here is actually the email (as configured in SecurityConfig)
        return userRepo.findByEmail(username)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}
```

**The flow when a user submits the login form:**

```
POST /authenticate (email=a@b.com, password=secret)
    │
    ├─ UsernamePasswordAuthenticationFilter intercepts
    ├─ Calls DaoAuthenticationProvider.authenticate()
    │       ├─ Calls userDetailService.loadUserByUsername("a@b.com")
    │       │       └─ userRepo.findByEmail("a@b.com") → User object
    │       └─ passwordEncoder.matches("secret", user.getPassword()) → true
    ├─ Creates UsernamePasswordAuthenticationToken (authenticated=true)
    ├─ Stores in SecurityContext
    └─ Redirects to /user/profile (successForwardUrl)
```

---

## 6. OAuth2 — Social Login (Google & GitHub)

### The OAuth2 Authorization Code Flow

```
User clicks "Login with Google"
    │
    ├─ Spring redirects to Google: /oauth2/authorization/google
    │       → Google OAuth URL with client_id, redirect_uri, scope
    │
    ├─ User approves on Google's consent screen
    │
    ├─ Google redirects back: /login/oauth2/code/google?code=AUTH_CODE
    │
    ├─ Spring exchanges AUTH_CODE for ACCESS_TOKEN (server-to-server, no browser)
    │
    ├─ Spring fetches user info: GET https://www.googleapis.com/userinfo
    │       → { email, name, picture, sub }
    │
    ├─ OAuthAuthenticationSuccessHandler.onAuthenticationSuccess() is called
    │
    └─ Redirected to /user/profile
```

### OAuthAuthenticationSuccessHandler — Complete Code Analysis

```java
@Component
public class OAuthAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    @Autowired
    private UserRepo userRepo;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        // 1. Identify the provider (google, github, linkedin)
        var oauth2AuthenicationToken = (OAuth2AuthenticationToken) authentication;
        String authorizedClientRegistrationId = 
            oauth2AuthenicationToken.getAuthorizedClientRegistrationId();
        // → "google" or "github"

        // 2. Get the principal with all user attributes
        var oauthUser = (DefaultOAuth2User) authentication.getPrincipal();
        // oauthUser.getAttributes() → Map of profile data from provider

        // 3. Build our User entity from the OAuth attributes
        User user = new User();
        user.setUserId(UUID.randomUUID().toString());
        user.setRoleList(List.of(AppConstants.ROLE_USER));
        user.setEmailVerified(true);  // OAuth providers verify emails
        user.setEnabled(true);
        user.setPassword("dummy");    // OAuth users don't have a real password

        if (authorizedClientRegistrationId.equalsIgnoreCase("google")) {
            user.setEmail(oauthUser.getAttribute("email").toString());
            user.setProfilePic(oauthUser.getAttribute("picture").toString());
            user.setName(oauthUser.getAttribute("name").toString());
            user.setProviderUserId(oauthUser.getName()); // Google's unique user ID
            user.setProvider(Providers.GOOGLE);
        } 
        else if (authorizedClientRegistrationId.equalsIgnoreCase("github")) {
            // GitHub may not provide email if user set it private
            String email = oauthUser.getAttribute("email") != null 
                ? oauthUser.getAttribute("email").toString()
                : oauthUser.getAttribute("login").toString() + "@gmail.com"; // Fallback
            
            user.setEmail(email);
            user.setProfilePic(oauthUser.getAttribute("avatar_url").toString());
            user.setName(oauthUser.getAttribute("login").toString());
            user.setProviderUserId(oauthUser.getName());
            user.setProvider(Providers.GITHUB);
        }

        // 4. Save only if user doesn't already exist (first-time login)
        User existingUser = userRepo.findByEmail(user.getEmail()).orElse(null);
        if (existingUser == null) {
            userRepo.save(user);
            // On subsequent logins, we reuse the existing record
        }

        // 5. Redirect to profile
        new DefaultRedirectStrategy().sendRedirect(request, response, "/user/profile");
    }
}
```

### Google vs GitHub OAuth Attributes

| Attribute | Google Key | GitHub Key |
|---|---|---|
| Email | `email` | `email` (can be null if private!) |
| Display Name | `name` | `login` (username) |
| Avatar | `picture` | `avatar_url` |
| Unique ID | `sub` (via `getName()`) | numeric id (via `getName()`) |

---

## 7. stream Key Generation

```java
@PostMapping("/generate-stream-key")
@ResponseBody
public ResponseEntity<String> generateStreamKey(Authentication authentication) {
    
    String email = null;
    Object principal = authentication.getPrincipal();
    
    // Handle all 3 authentication types:
    if (principal instanceof OidcUser) {
        email = ((OidcUser) principal).getEmail(); // Google OIDC
    } else if (principal instanceof OAuth2User) {
        email = ((OAuth2User) principal).getAttribute("email"); // GitHub
    } else {
        email = authentication.getName(); // Regular form login
    }
    
    User user = userService.getUserByEmail(email);
    
    // Only generate if no key exists (idempotent)
    if (user.getStreamKey() == null || user.getStreamKey().isEmpty()) {
        String streamKey = UUID.randomUUID().toString(); // e.g., "a1b2c3d4-..."
        user.setStreamKey(streamKey);
        userService.updateUser(user);
    }
    
    return ResponseEntity.ok(user.getStreamKey());
}
```

**Why UUID for stream key?** UUID v4 is 128-bit random — the probability of collision is astronomically low (`~5.3 × 10^-36`). It's unpredictable, making it suitable as a secret key for RTMP streams.

---

## 8. CSRF — Why Was It Disabled?

**CSRF (Cross-Site Request Forgery)** protection works by embedding a token in every form. The server validates the token on POST requests.

In StreamLite, CSRF was disabled because:
1. The `/upload` endpoint is a REST API called by JavaScript `fetch()` — CSRF tokens would complicate the frontend
2. The `/generate-stream-key` endpoint is called via AJAX
3. For a full production app, CSRF should be re-enabled with JWT or cookie-based CSRF tokens

```java
// What was tried first (incorrect):
httpSecurity.csrf(csrf ->
    csrf.ignoringRequestMatchers("/user/generate-stream-key")
);

// Then globally disabled (simpler but less secure):
httpSecurity.csrf(AbstractHttpConfigurer::disable);
```

---

## 9. The Providers Enum

```java
public enum Providers {
    SELF,       // Registered with email + password
    GOOGLE,     // OAuth2 Google login
    FACEBOOK,   // (planned)
    TWITTER,    // (planned)
    LINKEDIN,   // (placeholder in handler)
    GITHUB      // OAuth2 GitHub login
}
```

This is stored as a `STRING` in the database (`@Enumerated(value = EnumType.STRING)`).

---

## 10. Interview Questions on Security — Prepared Answers

**Q: What is the difference between authentication and authorization?**
> **Authentication** = proving who you are (login with email/password or Google OAuth)  
> **Authorization** = what you're allowed to do (only logged-in users can access `/user/**`)

**Q: How does Spring Security know which URLs require authentication?**
> Through the `SecurityFilterChain` bean's `authorizeHttpRequests` configuration. `/user/**` is `authenticated()`, everything else `permitAll()`.

**Q: What is DaoAuthenticationProvider?**
> It's Spring Security's default mechanism for database-backed authentication. It takes a `UserDetailsService` (which loads the user from DB) and a `PasswordEncoder` (which verifies the password). On login, it calls `loadUserByUsername()` and then `passwordEncoder.matches()`.

**Q: What does `@ElementCollection` do?**
> It maps a collection of basic types (like `List<String>`) to a separate table without creating a full `@Entity`. The `roleList` stores roles as strings in a `user_role_list` table, joined by the user's ID.

**Q: Why does BCrypt produce a different hash for the same password each time?**
> BCrypt generates a **random salt** (22 characters) for every hash operation. The salt is embedded in the output string. `matches()` extracts the salt from the stored hash and reapplies it to the plaintext before comparing.

**Q: What is the OAuth2 Authorization Code flow vs Implicit flow?**
> **Authorization Code (used here):** Auth code is exchanged for tokens server-to-server — the token never passes through the browser. Secure.  
> **Implicit flow (deprecated):** Token returned directly in URL fragment — exposed in browser history. Insecure, no longer recommended.

**Q: Can someone log in with both Google and their own password?**
> Currently no — the handler just saves new users on first OAuth2 login. If the email already exists (from form registration), the OAuth2 login reuses the existing user. But the `provider` field distinguishes how the account was created. A production system would need account-linking logic.
