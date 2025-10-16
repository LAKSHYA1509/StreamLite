## StreamLite Technology Stack Analysis & Dockerization Report

A detailed report of all technologies and components used in the StreamLite project:

### **Core Application Framework**
- **Spring Boot 3.3.1** - Main application framework
- **Java 17** - Runtime environment
- **Maven** - Build tool and dependency management

### **Backend Technologies**
- **Spring Web** - REST API development
- **Spring Data JPA** - Database operations with Hibernate
- **Spring Security** - Authentication and authorization with OAuth2
- **Spring WebSocket** - Real-time communication for chat
- **Spring Kafka** - Message streaming and processing
- **Spring Data Redis** - Caching and pub/sub messaging
- **Spring Actuator** - Application monitoring and metrics
- **Thymeleaf** - Server-side templating for web pages

### **External Dependencies & Services**

#### **Databases & Storage**
- **PostgreSQL** - Primary database (localhost:5432)
- **Redis** - Caching and pub/sub messaging (localhost:6379)
- **Apache Kafka** - Message broker (localhost:9092)

#### **Authentication Providers**
- **Google OAuth2** - Social login integration
- **GitHub OAuth2** - Social login integration

#### **Media Processing**
- **FFmpeg** - Video encoding, transcoding, and HLS segmentation
- **nginx-rtmp** - RTMP streaming server for live streaming

### **Frontend Technologies**
- **HTML5** - Web page structure
- **Tailwind CSS 3.4.18** - Utility-first CSS framework
- **JavaScript (ES6+)** - Client-side scripting
- **HLS.js** - HTTP Live Streaming player

### **Development & Build Tools**
- **Node.js** - JavaScript runtime for frontend builds
- **npm** - Package management for frontend dependencies
- **Maven Wrapper** - Maven build tool wrapper

### **Testing & Performance**
- **Locust** - Load testing framework (Python-based)
- **Prometheus** - Metrics collection and monitoring
- **Micrometer** - Application metrics facade

### **Current Manual Startup Requirements**

The project currently requires manual startup of multiple services:

1. **PostgreSQL Database** - Must be running on localhost:5432
2. **Redis Server** - Must be running on localhost:6379  
3. **Apache Kafka** - Must be running on localhost:9092
4. **nginx-rtmp Server** - For live streaming functionality
5. **Spring Boot Application** - Main application on port 8000
6. **FFmpeg** - For video processing and segmentation

### **File Processing Pipeline**

1. **Video Upload** - MP4 files uploaded via web interface
2. **Segmentation** - FFmpeg converts MP4 to HLS format using segmentation scripts
3. **Storage** - HLS segments stored in `videos/output/` directory
4. **Streaming** - Segments served via HTTP endpoints with Redis caching
5. **Playback** - HLS.js player consumes the streams

### **Key Configuration Details**

- **Application Port**: 8000
- **File Upload Limit**: 50MB
- **OAuth2 Clients**: Google and GitHub configured
- **Redis Channels**: "chat" for WebSocket messaging
- **HLS Segment Duration**: 10 seconds (configurable)
- **Video Storage**: Local filesystem with Redis caching layer

### **Dockerization Considerations**

#### **Multi-Container Architecture Required**
1. **Spring Boot App Container**
2. **PostgreSQL Container**
3. **Redis Container** 
4. **Kafka Container** (with Zookeeper)
5. **nginx-rtmp Container** (for live streaming)

#### **Volume Management**
- Video storage volumes for uploaded and processed content
- Database data persistence
- Kafka message persistence

#### **Network Configuration**
- Internal networking between containers
- Port mapping for external access (8000, 5432, 6379, 9092)
- RTMP port for live streaming

#### **Build Optimization**
- Multi-stage Docker builds for Java application
- Frontend asset optimization with Tailwind CSS
- FFmpeg binary inclusion for video processing

This analysis reveals a complex microservices architecture that requires careful orchestration for successful containerization. The current manual setup process involves multiple interdependent services that need to be properly sequenced and configured in a containerized environment.