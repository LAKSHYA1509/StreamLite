# STAGE 1: Build the application using Maven
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Build the application and create the JAR file
RUN mvn clean package -DskipTests

# STAGE 2: Create the final, lean image for running the application
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Install FFmpeg, which is required for video processing
RUN apt-get update && apt-get install -y ffmpeg

# Copy the JAR file from the builder stage
COPY --from=builder /app/target/*.jar app.jar

# Copy the video segmentation scripts
COPY segment_video.sh .
COPY segment_video.bat .

# Make the shell script executable
RUN chmod +x segment_video.sh

# Expose the application port
EXPOSE 8000

# Set the entrypoint to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]