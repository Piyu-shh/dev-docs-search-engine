# --- Build Stage ---
# Use an official Maven image to build the application JAR file
FROM maven:3.9-eclipse-temurin-17 AS build

# Set the working directory inside the container
WORKDIR /app

# Copy the Maven project definition file
COPY pom.xml .

# Copy the rest of the application source code
COPY src ./src

# Build the application and create the executable JAR file
RUN mvn clean package -DskipTests


# --- Run Stage ---
# CORRECTED: Use a current, official image for the Java 17 runtime
FROM eclipse-temurin:17-jre-focal

# Set the working directory
WORKDIR /app

# Copy the JAR file from the 'build' stage
# Make sure this JAR file name is correct for your project
COPY --from=build /app/target/search-0.0.1-SNAPSHOT.jar app.jar

# Expose port 8080 so Render can access our application
EXPOSE 8080

# The command to run the application
ENTRYPOINT ["java", "-jar", "app.jar"]