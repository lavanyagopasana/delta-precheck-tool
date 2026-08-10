# Multi-stage build: compile the CRA bundle with Node, serve the static output with nginx.
FROM node:20-alpine AS build
WORKDIR /build
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .
# REACT_APP_* vars are baked in at THIS step -- see src/config/runtimeConfig.js's own comments for
# why that's risky and why runtime-config.js (copied verbatim below) exists to override them
# without a rebuild.
RUN npm run build

FROM nginx:alpine AS runtime
COPY --from=build /build/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
