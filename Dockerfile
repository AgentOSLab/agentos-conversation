FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/conversation-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8011

ENTRYPOINT ["java", "-jar", "app.jar"]
