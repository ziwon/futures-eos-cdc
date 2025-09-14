set quiet := true

NAMESPACE := "trading"
STRIMZI_VERSION := "0.47.0"
OPS_NS := "strimzi-system"
APP_NS := "trading"

# Colors for output using tput
RED := `tput setaf 1`
GREEN := `tput setaf 2`
YELLOW := `tput setaf 3`
BLUE := `tput setaf 4`
CYAN := `tput setaf 6`
BOLD := `tput bold`
NC := `tput sgr0` # No Color

# === CLUSTER MANAGEMENT ===
@up:
	just kind-up ns strimzi-on
	just kafka topics postgres connect connector kcat kafka-ui

@down:
	just kind-down

@kind-up:
	kind create cluster --config deploy/kind-cluster.yaml
	@docker network connect kind kind-registry >/dev/null 2>&1 || true

@kind-down:
	kind delete cluster --name futures-eos

@ns:
	kubectl apply -f deploy/ns.yaml

# === KAFKA INFRASTRUCTURE ===
@strimzi-on:
	@echo "{{GREEN}}Installing Strimzi Operator...{{NC}}"
	helm repo add strimzi https://strimzi.io/charts/
	helm repo update
	helm upgrade --install strimzi-kafka-operator strimzi/strimzi-kafka-operator \
		--namespace {{OPS_NS}} \
		--version {{STRIMZI_VERSION}} \
		--set watchNamespaces[0]={{APP_NS}} \
		--set createGlobalResources=true \
		--wait --timeout 5m
	@echo "{{GREEN}}Strimzi Operator installed successfully{{NC}}"

@kafka:
	kubectl apply -n {{APP_NS}} -f deploy/strimzi/kafka/kafka.yaml
	kubectl wait --for=condition=Ready kafka/trading -n {{APP_NS}} --timeout=600s

@topics:
	kubectl apply -n {{APP_NS}} -f deploy/strimzi/kafka/topics.yaml

@connect:
	kubectl apply -n {{APP_NS}} -f deploy/strimzi/connects/kafka-connect.yaml
	kubectl apply -n {{APP_NS}} -f deploy/strimzi/connects/networkpolicy.yaml
	kubectl apply -n {{APP_NS}} -f deploy/strimzi/connects/ingress.yaml
	kubectl wait --for=condition=Ready kafkaconnect/trading-connect -n {{APP_NS}} --timeout=600s

@connector:
	kubectl apply -n {{APP_NS}} -f deploy/strimzi/connectors/pg-outbox-connector.yaml
	kubectl wait --for=condition=Ready kafkaconnector/pg-outbox -n {{APP_NS}} --timeout=300s

@kcat:
	kubectl apply -n {{APP_NS}} -f deploy/strimzi/tools/kcat.yaml

# === DATABASE ===
@postgres:
	kubectl apply -n {{APP_NS}} -f deploy/postgres/secret.yaml
	kubectl apply -n {{APP_NS}} -f deploy/postgres/configmap-init.yaml
	kubectl apply -n {{APP_NS}} -f deploy/postgres/statefulset.yaml
	kubectl apply -n {{APP_NS}} -f deploy/postgres/service.yaml
	kubectl rollout status sts/pg -n {{APP_NS}} --timeout=600s

# === KAFKA UI ===
@kafka-ui:
	kubectl apply -n {{APP_NS}} -f deploy/strimzi/monitoring/kafka-ui.yaml
	kubectl wait --for=condition=Ready pod -l app=kafka-ui -n {{APP_NS}} --timeout=120s
	@echo "{{GREEN}}Kafka UI deployed. Run 'just kafka-ui-pf' to access{{NC}}"

@kafka-ui-pf:
	@echo "{{GREEN}}Kafka UI available at http://localhost:8080{{NC}}"
	kubectl -n {{APP_NS}} port-forward svc/kafka-ui 8080:8080

@kafka-ui-logs:
	kubectl -n {{APP_NS}} logs -f deployment/kafka-ui

# === PORT FORWARDING ===
@pf:
	kubectl -n {{APP_NS}} port-forward svc/pg 5432:5432 &
	kubectl -n {{APP_NS}} port-forward svc/trading-kafka-bootstrap 9092:9092 &

# === LOCAL REGISTRY ===
@reg-up:
	@./scripts/kind-with-registry.sh

@reg-down:
	-docker rm -f kind-registry

@reg-status:
	docker ps | grep kind-registry || true

# === SIGNAL GENERATOR ===
@signal-generator:
	kubectl apply -n {{APP_NS}} -f deploy/signal-generator/configmap.yaml
	kubectl apply -n {{APP_NS}} -f deploy/signal-generator/cronjob-1m.yaml
	kubectl apply -n {{APP_NS}} -f deploy/signal-generator/cronjob-5m.yaml
	kubectl apply -n {{APP_NS}} -f deploy/signal-generator/cronjob-15m.yaml

@img-signal-generator:
	@if [ -x ./gradlew ]; then ./gradlew -Djib.allowInsecureRegistries=true :apps:signal-generator:jib; else gradle -Djib.allowInsecureRegistries=true :apps:signal-generator:jib; fi

# === SIGNAL PROCESSOR ===
@signal-processor: img-signal-processor processor-deploy
	@echo "{{GREEN}}Signal Processor deployed successfully{{NC}}"

@processor-deploy:
	kubectl apply -n {{APP_NS}} -f deploy/signal-processor/topic-decisions.yaml
	kubectl apply -n {{APP_NS}} -f deploy/signal-processor/deployment.yaml
	kubectl -n {{APP_NS}} rollout status deployment/signal-processor --timeout=300s

@img-signal-processor:
	@if [ -x ./gradlew ]; then ./gradlew -Djib.allowInsecureRegistries=true :apps:signal-processor:jib; else gradle -Djib.allowInsecureRegistries=true :apps:signal-processor:jib; fi

@processor-logs:
	kubectl -n {{APP_NS}} logs -f deployment/signal-processor

@processor-restart:
	kubectl -n {{APP_NS}} rollout restart deployment/signal-processor

# === MONITORING & TAILING ===
@tail-sig-1m:
	kubectl -n {{APP_NS}} exec -it kcat -- sh -lc "kcat -b trading-kafka-bootstrap:9092 -C -t trading.signal.1m -q -o -20 -e"

@tail-sig-5m:
	kubectl -n {{APP_NS}} exec -it kcat -- sh -lc "kcat -b trading-kafka-bootstrap:9092 -C -t trading.signal.5m -q -o -20 -e"

@tail-sig-15m:
	kubectl -n {{APP_NS}} exec -it kcat -- sh -lc "kcat -b trading-kafka-bootstrap:9092 -C -t trading.signal.15m -q -o -20 -e"

@tail-decisions:
	kubectl -n {{APP_NS}} exec -it kcat -- sh -lc "kcat -b trading-kafka-bootstrap:9092 -C -t trading.decisions -q -o -10 -e"

# === TESTING & DEMOS ===
# Burst Mode - Usage: just burst tf=1m n=1000
burst tf="1m" n="1000":
	@name="signal-burst-{{tf}}-`date +%s`" ; \
	cat deploy/signal-generator/burst-template.yaml \
	  | sed "s/__TF__/{{tf}}/g; s/__BURST__/{{n}}/g; s/__NAME__/$$name/g" \
	  | kubectl apply -n {{APP_NS}} -f -
	@echo "Job $$name created (TF={{tf}}, RATE_PER_SEC={{n}})."

# === EOS DEMONSTRATIONS ===
@eos-demo:
	@echo "{{BOLD}}{{BLUE}}Running Simple EOS Demo...{{NC}}"
	chmod +x scripts/eos-demo.sh
	./scripts/eos-demo.sh

@eos-verify:
	@echo "{{BOLD}}{{BLUE}}Running EOS Verification Demo...{{NC}}"
	chmod +x scripts/verify-eos.sh
	./scripts/verify-eos.sh

@eos-inject:
	@echo "{{YELLOW}}Injecting duplicate signals for EOS testing...{{NC}}"
	kubectl apply -n {{APP_NS}} -f deploy/eos-demo/duplicate-injector.yaml
	kubectl wait --for=condition=complete job/eos-duplicate-test -n {{APP_NS}} --timeout=60s || true
	kubectl logs job/eos-duplicate-test -n {{APP_NS}}
	kubectl delete job eos-duplicate-test -n {{APP_NS}}

@eos-monitor:
	@echo "{{GREEN}}Starting EOS Monitor...{{NC}}"
	kubectl apply -n {{APP_NS}} -f deploy/eos-demo/eos-monitor.yaml
	kubectl wait --for=condition=Ready pod/eos-monitor -n {{APP_NS}} --timeout=30s
	kubectl exec -it eos-monitor -n {{APP_NS}} -- bash /scripts/monitor-eos.sh

@eos-logs:
	@echo "{{YELLOW}}Showing EOS-related logs from signal processor...{{NC}}"
	kubectl logs -n {{APP_NS}} deployment/signal-processor --tail=50 | grep -E "EOS|exactly-once|transaction|commit|Decision" || true

@check-duplicates topic="trading.signal.1m":
	@echo "{{YELLOW}}Checking for duplicates in {{topic}}...{{NC}}"
	@echo "{{CYAN}}Analyzing last 100 messages...{{NC}}"
	@kubectl -n {{APP_NS}} exec kcat -- sh -c "kcat -b trading-kafka-bootstrap:9092 -C -t {{topic}} -q -o -100 -e" 2>/dev/null | sort | uniq -c | sort -rn | head -10
	@echo "{{CYAN}}Note: Numbers show occurrence count. Count > 1 indicates duplicates.{{NC}}"