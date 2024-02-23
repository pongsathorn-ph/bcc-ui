## Build docker image
```sh
docker build -f Dockerfile -t demo-api-dev:2024.02.00-0001-alpha . --no-cache
```

## Push to docker hub
```sh
docker tag demo-api-dev:2024.02.00-0001-alpha pongsathorn/demo-api-dev:2024.02.00-0001-alpha
docker push pongsathorn/demo-api-dev:2024.02.00-0001-alpha
```

#### Create ConfigMap
```sh
kubectl create configmap demo-ui-dev-properties-configmap --from-env-file=config-map-dev.properties --namespace=beam-namespace
```
