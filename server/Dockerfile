
FROM node:22-alpine

WORKDIR /app

COPY server.js ./
COPY lib ./lib

RUN mkdir -p /tmp/freeai-data && chown -R node:node /tmp/freeai-data

ENV PORT=8080
ENV DATA_DIR=/tmp/freeai-data

USER node

EXPOSE 8080

HEALTHCHECK CMD wget -qO- http://127.0.0.1:8080/health || exit 1

CMD ["node", "server.js"]
