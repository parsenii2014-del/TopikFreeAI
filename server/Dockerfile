FROM node:22-alpine
WORKDIR /app
COPY server.js ./
COPY lib ./lib
ENV PORT=8080 DATA_DIR=/data
VOLUME /data
USER node
EXPOSE 8080
HEALTHCHECK CMD wget -qO- http://127.0.0.1:8080/health || exit 1
CMD ["node", "server.js"]
