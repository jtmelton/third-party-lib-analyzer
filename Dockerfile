FROM anapsix/alpine-java
VOLUME /tmp
ADD target/third-party-lib-analyzer.jar app.jar
RUN bash -c 'touch /app.jar'
ENTRYPOINT java -Djava.security.egd=file:/dev/./urandom -jar /app.jar
