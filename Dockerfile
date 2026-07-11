FROM nginx:alpine
COPY src/main/resources/web/ /usr/share/nginx/html/
EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
