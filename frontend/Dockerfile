# Multi-stage build: compile the CRA bundle with Node, serve the static output with nginx.
FROM node:20-alpine AS build
WORKDIR /build
COPY package.json package-lock.json* ./
RUN npm ci
COPY . .

# REACT_APP_* vars are baked in at THIS step -- react-scripts only reads them from the environment
# of the `npm run build` process itself, not from whatever a container sets at `docker run` time.
# Each one needs an explicit ARG (received from `docker build --build-arg NAME=value`) re-exported
# as ENV, or a value passed via --build-arg with no matching ARG here is silently dropped and the
# build bakes in blank -- which is exactly what happened once already: a deployed build shipped with
# clientId:"" despite the right value having been passed at build time, because this file had no
# ARG declared to receive it. See src/config/runtimeConfig.js's own comments for why a wrong
# build-time value is risky even when this IS wired correctly, and why runtime-config.js (copied
# verbatim below) exists as a post-build override.
ARG REACT_APP_AZURE_CLIENT_ID
ARG REACT_APP_AZURE_TENANT_ID
ARG REACT_APP_AZURE_REDIRECT_URI
ARG REACT_APP_ALLOWED_EMAIL_DOMAIN
ARG REACT_APP_API_BASE
ENV REACT_APP_AZURE_CLIENT_ID=${REACT_APP_AZURE_CLIENT_ID} \
    REACT_APP_AZURE_TENANT_ID=${REACT_APP_AZURE_TENANT_ID} \
    REACT_APP_AZURE_REDIRECT_URI=${REACT_APP_AZURE_REDIRECT_URI} \
    REACT_APP_ALLOWED_EMAIL_DOMAIN=${REACT_APP_ALLOWED_EMAIL_DOMAIN} \
    REACT_APP_API_BASE=${REACT_APP_API_BASE}

RUN npm run build

FROM nginx:alpine AS runtime
COPY --from=build /build/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
