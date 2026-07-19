# PaySRE console (R6). Multi-stage build so the runtime image only
# carries the static dist.
FROM node:20-alpine AS build
WORKDIR /workspace
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 5173
HEALTHCHECK --interval=15s CMD wget -qO- http://localhost:5173/ >/dev/null || exit 1