FROM eclipse-temurin:21
RUN mkdir /opt/app
COPY ./gateleen-playground/target/playground.jar /opt/app/playground.jar
CMD ["java", "-jar", "/opt/app/playground.jar"]



