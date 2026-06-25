FROM node:22.13.1-bookworm-slim

WORKDIR /compat
COPY package.json package-lock.json /compat/
RUN npm ci --omit=dev
COPY compat.mjs /compat/compat.mjs
ENTRYPOINT ["node", "/compat/compat.mjs"]
