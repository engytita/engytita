IMG_REGISTRY ?= localhost:5001
CLIENT_IMG ?= $(IMG_REGISTRY)/client
SERVER_IMG ?= $(IMG_REGISTRY)/server
PROXY_IMG ?= $(IMG_REGISTRY)/proxy
POSTGRES_IMG ?= $(IMG_REGISTRY)/postgres
ENVOY_IMG ?= $(IMG_REGISTRY)/envoy

export CONTAINER_TOOL ?= docker

ifeq ($(NATIVE),true)
	QUARKUS_BUILD_ARGS = "-Pnative -Dquarkus.native.container-build=true"
endif

ifeq ($(OPENSHIFT),true)
	DEPLOY_BASE = openshift
else
	DEPLOY_BASE = kind
endif

.DEFAULT_GOAL := help

help:
	@awk '/^#/{c=substr($$0,3);next}c&&/^[[:alpha:]][[:alnum:]_-]+:/{print substr($$1,1,index($$1,":")),c}1{c=0}' $(MAKEFILE_LIST) | column -s: -t

.PHONY: client-image
## Build the client image
client-image:
	mvn package -am -pl test/client -Dquarkus.container-image.image=$(CLIENT_IMG) -Dquarkus.container-image.build=true $(QUARKUS_BUILD_ARGS)

.PHONY: client-push
## Push the client image
client-push:
	$(CONTAINER_TOOL) push $(CLIENT_IMG)

.PHONY: server-image
## Build the server image
server-image:
	mvn package -am -pl test/server -Dquarkus.container-image.image=$(SERVER_IMG) -Dquarkus.container-image.build=true $(QUARKUS_BUILD_ARGS)

.PHONY: server-push
## Push the server image
server-push:
	$(CONTAINER_TOOL) push $(SERVER_IMG)

.PHONY: proxy-image
## Build the proxy image
proxy-image:
	$(CONTAINER_TOOL) build test/proxy -f test/proxy/src/main/docker/Dockerfile.native-micro -t $(PROXY_IMG)

.PHONY: proxy-push
## Push the proxy image
proxy-push:
	$(CONTAINER_TOOL) push $(PROXY_IMG)

.PHONY: postgres-image
## Build the postgres image
postgres-image:
	$(CONTAINER_TOOL) build test/data -t $(POSTGRES_IMG)

.PHONY: postgres-push
## Push the postgres image
postgres-push:
	$(CONTAINER_TOOL) push $(POSTGRES_IMG)

.PHONY: envoy-image
## Build the envoy image
envoy-image:
	$(CONTAINER_TOOL) build test/filter -t $(ENVOY_IMG)

.PHONY: envoy-local-image
## Build the envoy image with a locally compiled WASM filter
envoy-local-image:
	$(CONTAINER_TOOL) build test/filter -t $(ENVOY_IMG) -f test/filter/Dockerfile.local

.PHONY: envoy-push
## Push the envoy image
envoy-push:
	$(CONTAINER_TOOL) push $(ENVOY_IMG)

.PHONY: build-all-images
## Build all images
build-all-images: client-image server-image proxy-image postgres-image envoy-image

.PHONY: push-all-images
## Push all images
push-all-images: client-push server-push proxy-push postgres-push envoy-push

.PHONY: kind-cluster
## Create a local kind cluster with image registry localhost:5000
kind-cluster:
	scripts/kind-with-registry.sh
# Load dependency images locally if available to stop them being repulled by kind
	kind load docker-image quay.io/infinispan/server:13.0 || true
	kind load docker-image redis:7.0.0 || true

.PHONY: deploy
## Deploy all components to the "demo" namespace on the local kind cluster
deploy:
	kubectl create namespace demo || true
	cd test/deploy/data && kustomize edit set image postgres=$(POSTGRES_IMG)
	kubectl kustomize test/deploy/data | kubectl -n demo apply -f -
# Wait for all backend services to be available before starting the client and server
	kubectl -n demo wait deployment postgres --for condition=Available=True --timeout=90s
	kubectl -n demo wait deployment infinispan --for condition=Available=True --timeout=90s
	kubectl -n demo wait deployment redis --for condition=Available=True --timeout=90s

	cd test/deploy/app/$(DEPLOY_BASE) && kustomize edit set image client=$(CLIENT_IMG) server=$(SERVER_IMG)
	kubectl kustomize test/deploy/app/$(DEPLOY_BASE) | kubectl -n demo apply -f -
	kubectl -n demo wait deployment airport-server --for condition=Available=True --timeout=90s
	kubectl -n demo wait deployment client --for condition=Available=True --timeout=90s

.PHONY: undeploy
## Undeploy all components deployed via the "deploy" target
undeploy:
	kubectl kustomize test/deploy/app/$(DEPLOY_BASE) | kubectl -n demo delete -f - || true
	kubectl kustomize test/deploy/data | kubectl -n demo delete -f - || true