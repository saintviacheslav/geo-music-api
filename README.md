# Geo Music API — гео-распределённое шардирование и репликация PostgreSQL

REST API на **Java 21 + Spring Boot 3.5**, которое демонстрирует преимущества гео-распределённой
архитектуры PostgreSQL: 3 региона (AMER, EURO, ASIA) × (1 master + 2 replica) = 9 узлов.

```
                    ┌──────────── AMER ────────────┐  ┌──────────── EURO ────────────┐  ┌──────────── ASIA ────────────┐
 запись своего      │ amer-master ──stream──► r1,r2 │  │ euro-master ──stream──► r1,r2 │  │ asia-master ──stream──► r1,r2 │
 шарда              └──────┬───────────────────────┘  └──────┬───────────────────────┘  └──────┬───────────────────────┘
                           └──────── logical replication (full mesh, origin = none) ──────────┘
```

* **Шардирование:** каждая строка пишется только на master своего региона
  (`User.homeRegion`, `Artist.originRegion`, `Album.regionCode`, избранное — регион пользователя).
* **Репликация:** master'а обмениваются данными через логическую репликацию, а их физические реплики
  получают всё по streaming replication. Поэтому каждая реплика содержит свой шард и копии чужих.
* **Маршрутизация:** запись идёт на master региона объекта, чтение — round-robin по репликам региона
  пользователя (`X-User-Region`, по умолчанию `EURO`).

## Структура проекта

| Путь | Назначение |
|---|---|
| `routing/GeoRoutingDataSource` | `AbstractRoutingDataSource`: выбор узла, round-robin, fail-over |
| `routing/RoutingContext`, `GeoTransactionExecutor` | контекст маршрута (регион + READ/WRITE) и запуск транзакций на нужном узле |
| `routing/NodeHealthRegistry`, `health/*` | состояние узлов, фоновые проверки, Health Indicators |
| `config/GeoDataSourceConfig`, `GeoSchemaMigrator` | пулы HikariCP по узлам, Flyway на каждом master'е |
| `observability/*` | метрики маршрутизации, атрибуты трассировки, конвенция HTTP-наблюдений |
| `api/*`, `service/*`, `domain/*` | REST-контроллеры, бизнес-логика, JPA-сущности |
| `src/main/resources/db/migration` | Flyway-миграции |
| `k6/` | нагрузочные сценарии |
| `infra/`, `docker-compose.yml` | 9 узлов PostgreSQL, центральный узел, Prometheus, Tempo, Grafana |

## Маршрутизация и отказоустойчивость

| Запрос | Узел |
|---|---|
| `POST /api/v1/users` | master региона `homeRegion` |
| `POST /api/v1/artists` | master региона `originRegion` |
| `POST /api/v1/albums` | master региона `regionCode` |
| `POST /api/v1/users/{userId}/favorites/{albumId}` | master домашнего региона пользователя |
| `GET /api/v1/albums/{id}`, `GET /api/v1/users/{userId}/feed`, `GET /api/v1/users/{id}`, `GET /api/v1/artists/{id}` | реплики региона из `X-User-Region` |

По умолчанию режим доступа определяется HTTP-методом: `GET` читает с реплик, `POST`/`PUT`/`DELETE` пишут
на master. Сервисы уточняют регион записи через `GeoTransactionExecutor.write(region, ...)`.

Отказоустойчивость:

1. Чтение: реплики региона перебираются по round-robin. Если реплика не отдаёт соединение
   (таймаут `DB_CONNECTION_TIMEOUT`, по умолчанию 2 с), запрос уходит на вторую реплику, а узел помечается
   `DOWN`. Следующие запросы его пропускают, пока фоновая проверка (`DB_HEALTH_PROBE_INTERVAL`) не вернёт его в работу.
2. Если недоступны все реплики, чтение идёт на локальный master.
3. Если при записи недоступен master, клиент получает `503 Service Unavailable` в формате ProblemDetail (RFC 7807):

```json
{
  "type": "https://geomusic.dev/problems/database-node-unavailable",
  "title": "Regional master unavailable",
  "status": 503,
  "detail": "Master node of region ASIA is unavailable",
  "instance": "/api/v1/users",
  "region": "ASIA",
  "operation": "WRITE",
  "attemptedNodes": ["asia-master"],
  "cause": "geo-asia-master - Connection is not available, request timed out after 2001ms.",
  "timestamp": "2026-10-07T09:00:00Z",
  "traceId": "6f0c..."
}
```

Каждый ответ содержит заголовок `X-Db-Node` с именем узла, который обслужил запрос (например, `euro-replica2`).

`/actuator/health` → компонент `geoDatabase` → регион → узел. Для каждого узла видны состояние, задержка
последней проверки и статистика пула (`active`, `idle`, `total`, `awaiting`). Упавшая реплика даёт статус
`DEGRADED` (HTTP 200), упавший master — `DOWN` (HTTP 503).

## Наблюдаемость

* **Prometheus:** `/actuator/prometheus`
  * `http_server_requests_seconds_*` — время ответа контроллеров с метками `user_region`, `db_target_region`, `db_node_type`;
  * `jdbc_query_seconds_*`, `jdbc_connection_seconds_*` — время SQL-запросов и удержания соединений (datasource-micrometer);
  * `geomusic_service_seconds_*` — время сервисных методов (`@Observed`);
  * `geo_db_routing_total{node, mode, failover}`, `geo_db_node_failures_total`, `geo_db_node_up` — маршрутизация и доступность узлов;
  * `hikaricp_connections_*{pool="geo-euro-replica1"...}` — пулы соединений.
* **Трассировка:** Micrometer Tracing + OpenTelemetry, экспорт по OTLP в Grafana Tempo
  (`OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`). HTTP-span содержит атрибуты `user.region`, `db.target.region`,
  `db.node.type`. Дочерние span'ы `connection` и `query` показывают конкретный узел (`db.node.name`) и SQL.
* **Grafana:** дашборд *Geo Music: sharding & replication* создаётся автоматически.

Вместо Micrometer Tracing можно использовать OpenTelemetry Java Agent. Тогда запускайте приложение с
`-javaagent:opentelemetry-javaagent.jar` и `management.tracing.enabled=false`, чтобы span'ы не дублировались.

## Запуск (WSL + Docker Engine)

```bash
cd /mnt/d/1education/тпсис/replication

# Весь стенд: 9 узлов + central-db + оба приложения + Prometheus/Tempo/Grafana
docker compose up -d --build

# Логическая репликация настраивается после того, как app-geo применит миграции
docker compose logs -f replication-setup      # ждать "Logical replication mesh is ready"

curl -s localhost:8080/actuator/health | jq '.components.geoDatabase'
```

Полная автоматическая проверка стенда с нуля (сборка, API, fail-over, метрики, трейсы, k6):

```bash
bash scripts/verify.sh 2>&1 | tee verify.log    # в конце: VERDICT: OK
```

| Сервис | Адрес |
|---|---|
| app-geo (гео-распределённый) | http://localhost:8080 |
| app-central (централизованный baseline) | http://localhost:8081 |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| PostgreSQL | master'а 6411/6421/6431, реплики 6412-6413/6422-6423/6432-6433, central 6440 |

Запуск приложения из IDE или через Maven (базы в Docker, настройки по умолчанию из `application.yml`
совпадают с портами `docker-compose.yml`):

```bash
docker compose up -d amer-master amer-replica1 amer-replica2 euro-master euro-replica1 euro-replica2 \
                     asia-master asia-replica1 asia-replica2 replication-setup
mvn spring-boot:run
```

Запуск обоих приложений из WSL без Docker-образа приложения (JDK 21 и Maven установлены через
`apt install openjdk-21-jdk maven`):

```bash
docker compose up -d amer-master amer-replica1 amer-replica2 euro-master euro-replica1 euro-replica2 \
                     asia-master asia-replica1 asia-replica2 central-db replication-setup
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
mvn -B package
java -jar target/geo-music-api.jar &                                   # app-geo на :8080

C=jdbc:postgresql://localhost:6440/geomusic                            # app-central на :8081
SERVER_PORT=8081 DEPLOYMENT_NAME=central \
AMER_MASTER_URL=$C AMER_REPLICA1_URL=$C AMER_REPLICA2_URL=$C \
EURO_MASTER_URL=$C EURO_REPLICA1_URL=$C EURO_REPLICA2_URL=$C \
ASIA_MASTER_URL=$C ASIA_REPLICA1_URL=$C ASIA_REPLICA2_URL=$C \
java -jar target/geo-music-api.jar &

k6 run k6/geo_distributed_test.js
k6 run k6/centralized_test.js
```

Сборка и тесты без локального Maven и JDK 21:

```bash
docker run --rm -v "$PWD":/workspace -v geo-m2:/root/.m2 -w /workspace maven:3.9-eclipse-temurin-21 mvn -B verify
```

## Примеры запросов

```bash
# Артист и альбом в шарде ASIA
ARTIST=$(curl -s -X POST localhost:8080/api/v1/artists -H 'Content-Type: application/json' \
  -d '{"name":"Yoko Kanno","originRegion":"ASIA","genre":"jazz"}' | jq -r .id)
ALBUM=$(curl -s -X POST localhost:8080/api/v1/albums -H 'Content-Type: application/json' \
  -d "{\"artistId\":\"$ARTIST\",\"title\":\"Cowboy Bebop OST\",\"releaseYear\":1998,\"regionCode\":\"ASIA\"}" | jq -r .id)

# Пользователь в шарде EURO
USER=$(curl -s -X POST localhost:8080/api/v1/users -H 'Content-Type: application/json' \
  -d '{"username":"anna","email":"anna@example.com","homeRegion":"EURO"}' | jq -r .id)

# Избранное пишется на euro-master; читаем альбом из ASIA с реплик EURO
curl -s -X POST localhost:8080/api/v1/users/$USER/favorites/$ALBUM -H 'X-User-Region: EURO'
curl -si localhost:8080/api/v1/albums/$ALBUM -H 'X-User-Region: EURO' | grep -i x-db-node
curl -s  localhost:8080/api/v1/users/$USER/feed -H 'X-User-Region: EURO' | jq
```

### Демонстрация fail-over

```bash
docker compose stop euro-replica1                      # чтение уходит на euro-replica2
for i in 1 2 3 4; do curl -si localhost:8080/api/v1/albums/$ALBUM -H 'X-User-Region: EURO' | grep -i x-db-node; done

docker compose stop euro-replica2                      # чтение уходит на euro-master
curl -si localhost:8080/api/v1/albums/$ALBUM -H 'X-User-Region: EURO' | grep -i x-db-node

docker compose stop asia-master                        # запись в ASIA -> 503 ProblemDetail
curl -s -X POST localhost:8080/api/v1/users -H 'Content-Type: application/json' \
  -d '{"username":"kenji","email":"kenji@example.com","homeRegion":"ASIA"}' | jq

docker compose start euro-replica1 euro-replica2 asia-master
```

## Нагрузочное тестирование (k6)

Оба сценария используют одинаковую модель нагрузки из `k6/lib/common.js`: в каждом регионе работает
отдельный сценарий `constant-arrival-rate`, 80 % запросов — чтение (альбом или лента), 20 % — запись
(пользователь, альбом или избранное в своём регионе). Перед нагрузкой `setup()` создаёт данные во всех
регионах и ждёт репликации.

| Скрипт | Что делает |
|---|---|
| `k6/centralized_test.js` | все регионы обращаются к одному центральному узлу (`app-central` + `central-db`), без `X-User-Region` |
| `k6/geo_distributed_test.js` | каждый регион передаёт `X-User-Region`: чтение с локальных реплик, запись на локальный master |

```bash
# через Docker; метрики k6 уходят в Prometheus и видны на дашборде Grafana
docker compose run --rm k6 run -o experimental-prometheus-rw --tag testid=central /scripts/centralized_test.js
docker compose run --rm k6 run -o experimental-prometheus-rw --tag testid=geo     /scripts/geo_distributed_test.js
```

Параметры (`-e NAME=value`): `RATE_PER_REGION` (50 запросов/с), `DURATION` (`2m`), `READ_RATIO` (0.8),
`BASE_URL`, `AMER_BASE_URL`/`EURO_BASE_URL`/`ASIA_BASE_URL` (отдельное приложение на регион),
`CENTRAL_BASE_URL`, `SEED_ALBUMS`, `SEED_USERS`, `REPLICATION_WAIT_SECONDS`.

На одной машине все контейнеры находятся «рядом», поэтому сетевую задержку между регионами нужно
эмулировать. Например, через `tc qdisc add dev eth0 root netem delay 80ms` в контейнерах удалённых узлов
(нужен `cap_add: [NET_ADMIN]`) или разнеся узлы по разным хостам. Без задержки сравнение показывает
в основном эффект распределения нагрузки по 9 узлам против одного.

## Ограничения модели данных

* Внешних ключей между шардами нет: строка, на которую ссылаются, может жить на другом master'е и
  прийти с задержкой репликации. Остался только `user_favorite_albums.user_id → users`, потому что обе
  строки пишутся в один шард.
* Глобальной уникальности `username`/`email` нет: одинаковое значение, вставленное в двух регионах,
  остановило бы apply-воркер логической репликации.
* Подписки создаются с `copy_data = false`. Данные, записанные до готовности `replication-setup`,
  останутся только в своём регионе. Сброс стенда: `docker compose down -v`.
