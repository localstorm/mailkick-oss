FROM amazoncorretto:21

ADD download/mailkick-server-1.0.0-SNAPSHOT.jar /app.jar
ADD download/credentials.sh /
ADD download/launch.sh /

RUN chmod +x /launch.sh /credentials.sh

CMD ["/launch.sh"]
