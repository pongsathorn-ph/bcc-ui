# Stage 1: Compile and Build angular codebase

# Use official more image as the base image
FROM node:20.10.0-slim as build

# Set the working directory
WORKDIR /usr/local/app

# Add the source code to app
COPY ./ /usr/local/app/

# Install all the dependencies
RUN npm install

# Generate the build of the application
RUN node_modules/.bin/ng build --configuration=production --output-path=dist --base-href=/demo-ui/ --aot=true
#RUN node_modules/.bin/ng build --configuration=dev --output-path=dist/dev --aot=true

# Stage 2: Serve app with nginx server

# Use official nginx image as the base image
FROM nginx:1.24.0-alpine3.17-slim

# Copy the build output to replace the default nginx contents
COPY --from=build /usr/local/app/dist/browser/ /usr/share/nginx/html

# Override default nginx configuration
COPY nginx-custom.conf /etc/nginx/conf.d/default.conf

#Expose port 80
EXPOSE 80

CMD ["/bin/sh",  "-c",  "envsubst < /usr/share/nginx/html/assets/env.sample.js > /usr/share/nginx/html/assets/env.js && exec nginx -g 'daemon off;'"]
