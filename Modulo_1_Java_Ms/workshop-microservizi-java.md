# 🧪 Workshop: Sviluppo a Microservizi in Java

### Hands-on Lab · Eleventh Training

> **Livello:** Junior (zero esperienza su Docker / Spring Boot / Compose)
> **Durata totale:** 20 ore (pause escluse)
> **Linguaggio:** Java 17 + Spring Boot 3
> **Stack:** Spring Boot · MySQL · Docker · Docker Compose · HTML/JS (FE minimale)

---

## 🎯 Obiettivi del workshop

Al termine del workshop sarai in grado di:

- Progettare e implementare **3 microservizi REST** in Java con Spring Boot (`bikes`, `cars`, `garage`)
- Persistere dati su **MySQL** usando Spring Data JPA
- **Containerizzare** un'applicazione Java con immagini Docker basate su Alpine
- Orchestrare uno stack multi-container con **Docker Compose**
- Far comunicare i microservizi tra loro tramite chiamate REST
- Realizzare un **frontend minimale** in HTML/JS che consuma i tre servizi
- (Opzionale) Aggiungere test, deploy su **AWS EKS**, e una **pipeline CI/CD su AWS**

---

## 🏗️ Architettura target

```
                        ┌──────────────────────┐
                        │   Frontend (HTML/JS) │
                        │   localhost:8080     │
                        └──────────┬───────────┘
                                   │
                ┌──────────────────┼──────────────────┐
                │                  │                  │
        ┌───────▼───────┐  ┌───────▼───────┐  ┌───────▼───────┐
        │ bikes-service │  │ cars-service  │  │ garage-service│
        │   :8081       │  │   :8082       │  │   :8083       │
        └───────┬───────┘  └───────┬───────┘  └───────┬───────┘
                │                  │                  │
        ┌───────▼───────┐  ┌───────▼───────┐  ┌───────▼───────┐
        │  bikes-db     │  │   cars-db     │  │  garage-db    │
        │  (MySQL)      │  │   (MySQL)     │  │  (MySQL)      │
        └───────────────┘  └───────────────┘  └───────────────┘
```

> 💡 Ogni microservizio ha il **suo database dedicato** (pattern *Database per Service*). Il servizio `garage` chiamerà `bikes` e `cars` via HTTP per validare i veicoli inseriti.

---

## 📚 Indice dei moduli

| Modulo | Titolo | Durata | Tipo |
|:------:|--------|:------:|:----:|
| 0 | Setup ambiente e prerequisiti | 1h 30m | Preliminare |
| 1 | Repository Git e Docker Hub | 30m | Preliminare |
| 2 | Primo microservizio: `bikes-service` | 3h | Obbligatorio |
| 3 | Secondo microservizio: `cars-service` | 1h 30m | Obbligatorio |
| 4 | Containerizzazione con Docker (Alpine) | 2h | Obbligatorio |
| 5 | Orchestrazione con Docker Compose | 2h | Obbligatorio |
| 6 | Microservizio `garage-service` (con comunicazione tra servizi) | 3h | Obbligatorio |
| 7 | Frontend minimale | 2h 30m | Obbligatorio |
| 8 | (Opzionale) Unit Test e Integration Test | 2h | Opzionale |
| 9 | (Opzionale) Deploy su AWS EKS | 1h 30m | Opzionale |
| 10 | (Opzionale) Pipeline CI/CD su AWS | 30m | Opzionale |

> ⏱️ **Totale obbligatori:** ~16 ore · **Preliminari:** ~2 ore · **Opzionali:** ~4 ore

---

## 🔧 Convenzioni del workshop

- I blocchi `bash` sono comandi da lanciare nel terminale
- I blocchi `java`, `xml`, `yaml` contengono codice da copiare nel progetto
- I riquadri **💡 Aiuto** contengono snippet pronti per i task complessi
- Ogni modulo termina con un **✅ Checkpoint** di verifica
- Le **📖 Risorse** in fondo a ogni modulo sono opzionali ma fortemente consigliate

---
---

# 🟢 MODULO 0 — Setup ambiente e prerequisiti

> ⏱️ **Durata stimata:** 1h 30m
> 🎯 **Obiettivi:** Avere sulla tua macchina tutto ciò che serve per sviluppare, eseguire e containerizzare microservizi Java.

## Cosa installerai

| Strumento | Versione | A cosa serve |
|-----------|---------|-------------|
| Java JDK | 17 (LTS) | Runtime e compilatore Java |
| Maven | 3.9+ | Build tool per progetti Java |
| Docker Desktop | latest | Containerizzazione |
| Docker Compose | v2 (incluso in Docker Desktop) | Orchestrazione multi-container |
| MySQL Client | 8.x | Test connessione DB (opzionale ma utile) |
| IDE | IntelliJ IDEA Community / VS Code | Sviluppo |
| `curl` | preinstallato | Test API REST |
| Git | 2.40+ | Versionamento |

---

## Task 0.1 — Installa Java 17

**Su macOS (con Homebrew):**
```bash
brew install openjdk@17
sudo ln -sfn $(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk \
  /Library/Java/JavaVirtualMachines/openjdk-17.jdk
```

**Su Windows:** scarica l'installer da [Adoptium Temurin 17](https://adoptium.net/temurin/releases/?version=17).

**Su Linux (Ubuntu/Debian):**
```bash
sudo apt update && sudo apt install -y openjdk-17-jdk
```

**Verifica:**
```bash
java -version
# deve stampare: openjdk version "17.x.x"
```

## Task 0.2 — Installa Maven

**macOS:** `brew install maven`
**Linux:** `sudo apt install -y maven`
**Windows:** scarica da [maven.apache.org/download.cgi](https://maven.apache.org/download.cgi) e aggiungi `bin` al `PATH`.

**Verifica:**
```bash
mvn -version
# deve stampare: Apache Maven 3.9.x
```

## Task 0.3 — Installa Docker Desktop

Scarica e installa **Docker Desktop** dal sito ufficiale: [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/).

> ⚠️ Su Windows assicurati di abilitare **WSL2 backend** durante l'installazione.

**Verifica:**
```bash
docker --version
docker compose version
docker run hello-world   # deve stampare il messaggio di benvenuto Docker
```

## Task 0.4 — Installa l'IDE

Scegli uno dei due:
- **IntelliJ IDEA Community Edition** → [jetbrains.com/idea/download](https://www.jetbrains.com/idea/download/) (consigliato per Java)
- **Visual Studio Code** + estensione *Extension Pack for Java* → [code.visualstudio.com](https://code.visualstudio.com/)

## Task 0.5 — (Opzionale ma utile) Client MySQL

```bash
# macOS
brew install mysql-client

# Linux
sudo apt install -y mysql-client
```

Oppure installa **DBeaver** (GUI multipiattaforma): [dbeaver.io](https://dbeaver.io/).

---

## ✅ Checkpoint Modulo 0

Esegui questi comandi: tutti devono restituire una versione valida.

```bash
java -version          # 17.x.x
mvn -version           # 3.9.x
docker --version       # 20.x+
docker compose version # v2.x
git --version          # 2.x
curl --version         # qualsiasi
```

> 📖 **Approfondimenti:** [Java vs JDK vs JRE](https://docs.oracle.com/en/java/javase/17/), [Cosa è Maven](https://maven.apache.org/what-is-maven.html), [Cosa è Docker](https://docs.docker.com/get-started/overview/)

---
---

# 🟢 MODULO 1 — Repository Git e Docker Hub

> ⏱️ **Durata stimata:** 30m
> 🎯 **Obiettivi:** Avere un repository Git pronto e un account Docker Hub configurato per pubblicare le immagini.

## Introduzione

**Git** è il sistema di versionamento del codice. **Docker Hub** è il registry pubblico dove pubblicheremo le immagini Docker dei nostri microservizi (potresti usare anche AWS ECR, GitHub Container Registry, ecc.).

## Task 1.1 — Crea il repository Git

```bash
mkdir workshop-microservices-java
cd workshop-microservices-java
git init
```

Crea un file `.gitignore` alla radice:

```gitignore
# Maven
target/
*.class
.mvn/

# IDE
.idea/
*.iml
.vscode/
.project
.classpath
.settings/

# OS
.DS_Store
Thumbs.db

# Logs
*.log

# Env files
.env
```

Crea un primo `README.md`:

```markdown
# Workshop Microservizi Java

Workshop pratico Eleventh sui microservizi in Java con Spring Boot, MySQL e Docker.
```

Primo commit:

```bash
git add .
git commit -m "chore: initial setup"
```

## Task 1.2 — Pubblica su GitHub

Crea un repository **public** su [github.com/new](https://github.com/new) con nome `workshop-microservices-java`, **senza** README/`.gitignore` (li hai già).

Poi:

```bash
git remote add origin git@github.com:<tuo-username>/workshop-microservices-java.git
git branch -M main
git push -u origin main
```

## Task 1.3 — Account Docker Hub

1. Registrati su [hub.docker.com](https://hub.docker.com/) (è gratuito).
2. Annota il tuo username (es. `mariorossi`).
3. Login da terminale:
   ```bash
   docker login
   # inserisci username e password (o personal access token)
   ```

## ✅ Checkpoint Modulo 1

```bash
git remote -v             # mostra il remote origin
docker login              # deve dire "Login Succeeded"
```

> 📖 **Approfondimenti:** [Pro Git Book (free)](https://git-scm.com/book/en/v2), [Docker Hub Quickstart](https://docs.docker.com/docker-hub/quickstart/)

---
---

# 🟢 MODULO 2 — Primo microservizio: `bikes-service`

> ⏱️ **Durata stimata:** 3 ore
> 🎯 **Obiettivi:** Creare il tuo primo microservizio Spring Boot con CRUD completo, persistenza MySQL e API REST testabili con `curl`.

## Introduzione a Spring Boot

**Spring Boot** è il framework Java più popolare per creare applicazioni backend. Con poche righe di configurazione ottieni:
- Un server web embedded (**Tomcat**) → l'app è eseguibile come JAR
- **Auto-configurazione**: rileva le dipendenze (es. MySQL) e configura il necessario
- **Spring Data JPA**: genera operazioni CRUD a partire da un'interfaccia
- **Spring Web**: scrivi controller REST con annotazioni (`@RestController`, `@GetMapping`...)

> 📖 [Spring Boot Reference](https://docs.spring.io/spring-boot/index.html) · [Spring Initializr](https://start.spring.io/)

---

## Task 2.1 — Genera lo scheletro del progetto

Vai su [start.spring.io](https://start.spring.io/) e configura:

| Campo | Valore |
|-------|--------|
| Project | Maven |
| Language | Java |
| Spring Boot | 3.2.x (o ultima stabile) |
| Group | `com.eleventh.workshop` |
| Artifact | `bikes-service` |
| Packaging | Jar |
| Java | 17 |

**Dependencies da aggiungere:**
- Spring Web
- Spring Data JPA
- MySQL Driver
- Spring Boot DevTools (opzionale, comodo)
- Validation
- Lombok (opzionale, riduce boilerplate)

Clicca **GENERATE**, scarica lo zip, scompattalo dentro `workshop-microservices-java/bikes-service/`.

## Task 2.2 — Avvia MySQL in locale (con Docker, già pronto)

Per ora useremo MySQL via Docker (anticipiamo un pezzo del Modulo 4):

```bash
docker run -d \
  --name mysql-bikes \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=bikesdb \
  -e MYSQL_USER=bikesuser \
  -e MYSQL_PASSWORD=bikespwd \
  -p 3307:3306 \
  mysql:8.0
```

> Usiamo la porta `3307` per non collidere con un eventuale MySQL già presente sulla macchina.

**Verifica:**
```bash
docker ps | grep mysql-bikes
docker logs mysql-bikes | tail -20
# attendi finché vedi: "ready for connections"
```

## Task 2.3 — Configura `application.yml`

Sostituisci `src/main/resources/application.properties` con `application.yml`:

```yaml
server:
  port: 8081

spring:
  application:
    name: bikes-service
  datasource:
    url: jdbc:mysql://localhost:3307/bikesdb
    username: bikesuser
    password: bikespwd
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQLDialect
```

> ⚠️ `ddl-auto: update` crea/aggiorna automaticamente le tabelle dall'entity. In produzione si usa `validate` + Flyway/Liquibase.

## Task 2.4 — Crea l'Entity `Bike`

Crea il package `com.eleventh.workshop.bikesservice.model` e dentro la classe:

> 💡 **Aiuto** — Snippet pronto:
> ```java
> package com.eleventh.workshop.bikesservice.model;
>
> import jakarta.persistence.*;
> import jakarta.validation.constraints.*;
>
> @Entity
> @Table(name = "bikes")
> public class Bike {
>
>     @Id
>     @GeneratedValue(strategy = GenerationType.IDENTITY)
>     private Long id;
>
>     @NotBlank
>     private String brand;        // es. Ducati
>
>     @NotBlank
>     private String model;        // es. Panigale V4
>
>     @Min(50)
>     private Integer engineCc;    // cilindrata in cc
>
>     @NotBlank
>     private String type;         // sport, naked, enduro, scooter...
>
>     @Min(1900)
>     private Integer year;
>
>     @Min(0)
>     private Double price;
>
>     // getter & setter (oppure @Data di Lombok)
>     public Long getId() { return id; }
>     public void setId(Long id) { this.id = id; }
>     public String getBrand() { return brand; }
>     public void setBrand(String brand) { this.brand = brand; }
>     public String getModel() { return model; }
>     public void setModel(String model) { this.model = model; }
>     public Integer getEngineCc() { return engineCc; }
>     public void setEngineCc(Integer engineCc) { this.engineCc = engineCc; }
>     public String getType() { return type; }
>     public void setType(String type) { this.type = type; }
>     public Integer getYear() { return year; }
>     public void setYear(Integer year) { this.year = year; }
>     public Double getPrice() { return price; }
>     public void setPrice(Double price) { this.price = price; }
> }
> ```

## Task 2.5 — Crea il Repository

Package `com.eleventh.workshop.bikesservice.repository`:

```java
package com.eleventh.workshop.bikesservice.repository;

import com.eleventh.workshop.bikesservice.model.Bike;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BikeRepository extends JpaRepository<Bike, Long> {
}
```

> 💡 Spring Data JPA implementa **automaticamente** le operazioni CRUD: `findAll`, `findById`, `save`, `deleteById`...

## Task 2.6 — Crea il Service

Package `com.eleventh.workshop.bikesservice.service`:

> 💡 **Aiuto** — Snippet pronto:
> ```java
> package com.eleventh.workshop.bikesservice.service;
>
> import com.eleventh.workshop.bikesservice.model.Bike;
> import com.eleventh.workshop.bikesservice.repository.BikeRepository;
> import org.springframework.stereotype.Service;
>
> import java.util.List;
> import java.util.Optional;
>
> @Service
> public class BikeService {
>
>     private final BikeRepository repository;
>
>     public BikeService(BikeRepository repository) {
>         this.repository = repository;
>     }
>
>     public List<Bike> findAll() { return repository.findAll(); }
>
>     public Optional<Bike> findById(Long id) { return repository.findById(id); }
>
>     public Bike save(Bike bike) { return repository.save(bike); }
>
>     public Bike update(Long id, Bike updated) {
>         return repository.findById(id)
>             .map(existing -> {
>                 existing.setBrand(updated.getBrand());
>                 existing.setModel(updated.getModel());
>                 existing.setEngineCc(updated.getEngineCc());
>                 existing.setType(updated.getType());
>                 existing.setYear(updated.getYear());
>                 existing.setPrice(updated.getPrice());
>                 return repository.save(existing);
>             })
>             .orElseThrow(() -> new RuntimeException("Bike not found: " + id));
>     }
>
>     public void delete(Long id) { repository.deleteById(id); }
> }
> ```

## Task 2.7 — Crea il Controller REST

Package `com.eleventh.workshop.bikesservice.controller`:

> 💡 **Aiuto** — Snippet pronto:
> ```java
> package com.eleventh.workshop.bikesservice.controller;
>
> import com.eleventh.workshop.bikesservice.model.Bike;
> import com.eleventh.workshop.bikesservice.service.BikeService;
> import jakarta.validation.Valid;
> import org.springframework.http.ResponseEntity;
> import org.springframework.web.bind.annotation.*;
>
> import java.util.List;
>
> @RestController
> @RequestMapping("/api/bikes")
> @CrossOrigin(origins = "*")  // permette al FE di chiamare il servizio
> public class BikeController {
>
>     private final BikeService service;
>
>     public BikeController(BikeService service) {
>         this.service = service;
>     }
>
>     @GetMapping
>     public List<Bike> getAll() { return service.findAll(); }
>
>     @GetMapping("/{id}")
>     public ResponseEntity<Bike> getById(@PathVariable Long id) {
>         return service.findById(id)
>             .map(ResponseEntity::ok)
>             .orElse(ResponseEntity.notFound().build());
>     }
>
>     @PostMapping
>     public ResponseEntity<Bike> create(@Valid @RequestBody Bike bike) {
>         return ResponseEntity.status(201).body(service.save(bike));
>     }
>
>     @PutMapping("/{id}")
>     public ResponseEntity<Bike> update(@PathVariable Long id, @Valid @RequestBody Bike bike) {
>         return ResponseEntity.ok(service.update(id, bike));
>     }
>
>     @DeleteMapping("/{id}")
>     public ResponseEntity<Void> delete(@PathVariable Long id) {
>         service.delete(id);
>         return ResponseEntity.noContent().build();
>     }
> }
> ```

## Task 2.8 — Avvia il microservizio in locale

```bash
cd bikes-service
./mvnw spring-boot:run
# (su Windows: mvnw.cmd spring-boot:run)
```

Devi vedere nei log:
```
Tomcat started on port 8081
Started BikesServiceApplication in X.XXX seconds
```

## ✅ Checkpoint Modulo 2 — Test con `curl`

In un altro terminale:

```bash
# 1. Lista vuota
curl -s http://localhost:8081/api/bikes
# []

# 2. Crea una moto
curl -s -X POST http://localhost:8081/api/bikes \
  -H "Content-Type: application/json" \
  -d '{
    "brand": "Ducati",
    "model": "Panigale V4",
    "engineCc": 1103,
    "type": "sport",
    "year": 2024,
    "price": 28000.00
  }'
# {"id":1,"brand":"Ducati",...}

# 3. Recupera by id
curl -s http://localhost:8081/api/bikes/1

# 4. Aggiorna
curl -s -X PUT http://localhost:8081/api/bikes/1 \
  -H "Content-Type: application/json" \
  -d '{"brand":"Ducati","model":"Panigale V4 S","engineCc":1103,"type":"sport","year":2024,"price":32000}'

# 5. Lista
curl -s http://localhost:8081/api/bikes

# 6. Elimina
curl -s -X DELETE http://localhost:8081/api/bikes/1 -i
# HTTP/1.1 204 No Content
```

> 📖 **Approfondimenti:** [Spring Web MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html), [Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/), [REST API best practices](https://restfulapi.net/)

---
---

# 🟢 MODULO 3 — Secondo microservizio: `cars-service`

> ⏱️ **Durata stimata:** 1h 30m
> 🎯 **Obiettivi:** Replicare il pattern del Modulo 2 in autonomia, scoprendo cosa è davvero un "microservizio replicabile".

## Introduzione

Ora che hai capito il pattern (entity → repository → service → controller), creare un secondo microservizio è **molto più veloce**. È esattamente questo il vantaggio di Spring Boot: aderisci a una convenzione e scrivi solo la logica di business.

## Task 3.1 — Genera il progetto

Da [start.spring.io](https://start.spring.io/) crea un secondo progetto:
- Artifact: `cars-service`
- stesse dipendenze del Modulo 2

Scompattalo in `workshop-microservices-java/cars-service/`.

## Task 3.2 — Database `carsdb`

```bash
docker run -d \
  --name mysql-cars \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=carsdb \
  -e MYSQL_USER=carsuser \
  -e MYSQL_PASSWORD=carspwd \
  -p 3308:3306 \
  mysql:8.0
```

## Task 3.3 — Configura `application.yml`

```yaml
server:
  port: 8082

spring:
  application:
    name: cars-service
  datasource:
    url: jdbc:mysql://localhost:3308/carsdb
    username: carsuser
    password: carspwd
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
```

## Task 3.4 — Entity `Car`

> 💡 **Aiuto** — Campi suggeriti per `Car`:
> ```java
> @Entity
> @Table(name = "cars")
> public class Car {
>     @Id
>     @GeneratedValue(strategy = GenerationType.IDENTITY)
>     private Long id;
>
>     @NotBlank private String brand;        // Fiat
>     @NotBlank private String model;        // Panda
>     @NotBlank private String fuelType;     // petrol, diesel, electric, hybrid
>     @Min(2)   private Integer doors;
>     @Min(2)   private Integer seats;
>     @Min(1900) private Integer year;
>     @Min(0)   private Double price;
>
>     // ... getter / setter
> }
> ```

## Task 3.5 — Repository, Service, Controller

Replica gli stessi pattern del Modulo 2, sostituendo `Bike` con `Car`. Endpoint base: `/api/cars`.

> 💡 **Aiuto** — Se sei in difficoltà, copia letteralmente i file dal modulo 2 e fai un find-replace `Bike` → `Car`, `bike` → `car`. È esattamente quello che faresti nella vita reale per un nuovo CRUD.

## Task 3.6 — Avvia il servizio

```bash
cd cars-service
./mvnw spring-boot:run
```

## ✅ Checkpoint Modulo 3

```bash
# Crea una macchina
curl -s -X POST http://localhost:8082/api/cars \
  -H "Content-Type: application/json" \
  -d '{
    "brand": "Fiat",
    "model": "Panda",
    "fuelType": "hybrid",
    "doors": 5,
    "seats": 5,
    "year": 2024,
    "price": 17500
  }'

# Lista
curl -s http://localhost:8082/api/cars
```

I servizi `bikes` e `cars` ora **girano contemporaneamente** sulle porte `8081` e `8082`. Lascia entrambi attivi.

> 📖 **Approfondimenti:** [12-Factor App](https://12factor.net/), [Microservices.io patterns](https://microservices.io/patterns/microservices.html)

---
---

# 🟢 MODULO 4 — Containerizzazione con Docker (Alpine)

> ⏱️ **Durata stimata:** 2 ore
> 🎯 **Obiettivi:** Creare immagini Docker leggere (Alpine) per `bikes-service` e `cars-service`, pubblicarle su Docker Hub.

## Introduzione a Docker

Un **container** è un processo isolato che gira con la propria filesystem, le proprie librerie e la propria rete. È più leggero di una VM perché condivide il kernel dell'host.

Un **Dockerfile** è la "ricetta" per costruire un'**immagine**: un template immutabile da cui istanziare container.

**Alpine Linux** è una distro Linux minimale (~5 MB). Usandola come base ottieni immagini molto piccole — perfette per microservizi.

> 📖 [Docker Concepts](https://docs.docker.com/get-started/overview/), [Best practices for Dockerfiles](https://docs.docker.com/develop/develop-images/dockerfile_best-practices/)

---

## Task 4.1 — Crea il `Dockerfile` per `bikes-service`

Dentro `bikes-service/Dockerfile`:

> 💡 **Aiuto** — `Dockerfile` multi-stage pronto:
> ```dockerfile
> # ===== STAGE 1: Build =====
> FROM maven:3.9-eclipse-temurin-17-alpine AS build
> WORKDIR /build
>
> COPY pom.xml .
> COPY src ./src
>
> RUN mvn clean package -DskipTests
>
> # ===== STAGE 2: Runtime =====
> FROM eclipse-temurin:17-jre-alpine
> WORKDIR /app
>
> # utente non-root per sicurezza
> RUN addgroup -S spring && adduser -S spring -G spring
> USER spring:spring
>
> COPY --from=build /build/target/*.jar app.jar
>
> EXPOSE 8081
>
> ENTRYPOINT ["java", "-jar", "/app/app.jar"]
> ```

> 🧠 **Cosa abbiamo fatto:**
> 1. **Stage 1 (build):** usiamo un'immagine con Maven per compilare il JAR
> 2. **Stage 2 (runtime):** copiamo solo il JAR in un'immagine **JRE Alpine** (~80 MB invece di ~400 MB)
> 3. **Utente non-root** per sicurezza

## Task 4.2 — Crea un `.dockerignore`

Dentro `bikes-service/.dockerignore`:
```
target/
.git/
.idea/
*.iml
.vscode/
README.md
```

Evita di copiare `target/` (che contiene il JAR locale già compilato) e altri file inutili nel contesto di build.

## Task 4.3 — Build dell'immagine

```bash
cd bikes-service
docker build -t <tuo-username>/bikes-service:0.1.0 .
```

**Verifica:**
```bash
docker images | grep bikes-service
# <tuo-username>/bikes-service   0.1.0   ...   ~180MB
```

## Task 4.4 — Esegui il container

> ⚠️ Il container deve potersi connettere a MySQL. Su Mac/Windows MySQL gira su `host.docker.internal:3307`. Su Linux, usa `--add-host=host.docker.internal:host-gateway`.

```bash
docker run -d \
  --name bikes-service \
  -p 8081:8081 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3307/bikesdb \
  -e SPRING_DATASOURCE_USERNAME=bikesuser \
  -e SPRING_DATASOURCE_PASSWORD=bikespwd \
  <tuo-username>/bikes-service:0.1.0

# vedi i log
docker logs -f bikes-service
```

> 💡 Le variabili `SPRING_*` **sovrascrivono** la configurazione di `application.yml`. È la magia di Spring Boot: zero modifiche al codice per andare in container.

## Task 4.5 — Ripeti per `cars-service`

Replica `Dockerfile` e `.dockerignore` cambiando `EXPOSE 8081` → `EXPOSE 8082`.

```bash
cd ../cars-service
docker build -t <tuo-username>/cars-service:0.1.0 .

docker run -d \
  --name cars-service \
  -p 8082:8082 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3308/carsdb \
  -e SPRING_DATASOURCE_USERNAME=carsuser \
  -e SPRING_DATASOURCE_PASSWORD=carspwd \
  <tuo-username>/cars-service:0.1.0
```

## Task 4.6 — Pubblica le immagini su Docker Hub

```bash
docker push <tuo-username>/bikes-service:0.1.0
docker push <tuo-username>/cars-service:0.1.0
```

Verifica su [hub.docker.com/repositories](https://hub.docker.com/repositories) — devi vedere i due repository.

## ✅ Checkpoint Modulo 4

```bash
# entrambi i container devono rispondere
curl -s http://localhost:8081/api/bikes
curl -s http://localhost:8082/api/cars

# entrambe le immagini su Docker Hub
docker search <tuo-username>
```

> 📖 **Approfondimenti:** [Multi-stage builds](https://docs.docker.com/build/building/multi-stage/), [Distroless images](https://github.com/GoogleContainerTools/distroless), [Spring Boot Docker guide](https://spring.io/guides/topicals/spring-boot-docker)

---
---

# 🟢 MODULO 5 — Orchestrazione con Docker Compose

> ⏱️ **Durata stimata:** 2 ore
> 🎯 **Obiettivi:** Avviare con un solo comando l'intero stack: 2 microservizi + 2 database, con rete dedicata e dipendenze ordinate.

## Introduzione a Docker Compose

Quando hai più container che devono lavorare insieme, gestirli con `docker run` diventa fragile. **Docker Compose** descrive l'intero stack in un file YAML e lo orchestra con un solo comando.

> 📖 [Compose specification](https://compose-spec.io/), [Docker Compose docs](https://docs.docker.com/compose/)

---

## Task 5.1 — Cleanup dei container precedenti

```bash
docker stop mysql-bikes mysql-cars bikes-service cars-service 2>/dev/null
docker rm mysql-bikes mysql-cars bikes-service cars-service 2>/dev/null
```

## Task 5.2 — Crea il `docker-compose.yml` alla radice

Nella radice di `workshop-microservices-java/`:

> 💡 **Aiuto** — `docker-compose.yml` pronto:
> ```yaml
> services:
>
>   bikes-db:
>     image: mysql:8.0
>     container_name: bikes-db
>     environment:
>       MYSQL_ROOT_PASSWORD: root
>       MYSQL_DATABASE: bikesdb
>       MYSQL_USER: bikesuser
>       MYSQL_PASSWORD: bikespwd
>     volumes:
>       - bikes-data:/var/lib/mysql
>     networks:
>       - workshop-net
>     healthcheck:
>       test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot"]
>       interval: 5s
>       retries: 10
>
>   cars-db:
>     image: mysql:8.0
>     container_name: cars-db
>     environment:
>       MYSQL_ROOT_PASSWORD: root
>       MYSQL_DATABASE: carsdb
>       MYSQL_USER: carsuser
>       MYSQL_PASSWORD: carspwd
>     volumes:
>       - cars-data:/var/lib/mysql
>     networks:
>       - workshop-net
>     healthcheck:
>       test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot"]
>       interval: 5s
>       retries: 10
>
>   bikes-service:
>     build: ./bikes-service
>     container_name: bikes-service
>     ports:
>       - "8081:8081"
>     environment:
>       SPRING_DATASOURCE_URL: jdbc:mysql://bikes-db:3306/bikesdb
>       SPRING_DATASOURCE_USERNAME: bikesuser
>       SPRING_DATASOURCE_PASSWORD: bikespwd
>     depends_on:
>       bikes-db:
>         condition: service_healthy
>     networks:
>       - workshop-net
>
>   cars-service:
>     build: ./cars-service
>     container_name: cars-service
>     ports:
>       - "8082:8082"
>     environment:
>       SPRING_DATASOURCE_URL: jdbc:mysql://cars-db:3306/carsdb
>       SPRING_DATASOURCE_USERNAME: carsuser
>       SPRING_DATASOURCE_PASSWORD: carspwd
>     depends_on:
>       cars-db:
>         condition: service_healthy
>     networks:
>       - workshop-net
>
> volumes:
>   bikes-data:
>   cars-data:
>
> networks:
>   workshop-net:
>     driver: bridge
> ```

> 🧠 **Cose importanti:**
> - **`networks`**: tutti i container nello stesso network si vedono per **nome del servizio** (`bikes-db`, `cars-db`...). Niente `host.docker.internal` qui.
> - **`depends_on` + `healthcheck`**: il servizio Spring Boot non parte finché il DB non è davvero pronto.
> - **`volumes`**: i dati MySQL persistono tra `compose down` e `compose up`.

## Task 5.3 — Avvia lo stack

```bash
docker compose up --build
```

> Lascia il terminale aperto: vedrai i log di tutti i container in tempo reale. Per fermare: `Ctrl+C`.

In alternativa, in background:
```bash
docker compose up -d --build
docker compose logs -f bikes-service
```

## Task 5.4 — Comandi utili Compose

```bash
docker compose ps              # stato dei servizi
docker compose logs -f         # tutti i log
docker compose logs -f cars-service   # log di un solo servizio
docker compose restart bikes-service
docker compose down            # ferma e rimuove i container
docker compose down -v         # ...e cancella anche i volumi (⚠️ cancella i dati)
```

## ✅ Checkpoint Modulo 5

```bash
docker compose ps
# tutti i servizi devono essere "Up" / "healthy"

curl -s http://localhost:8081/api/bikes
curl -s http://localhost:8082/api/cars

# crea una bike e una car attraverso lo stack containerizzato
curl -X POST http://localhost:8081/api/bikes -H "Content-Type: application/json" \
  -d '{"brand":"BMW","model":"R 1250 GS","engineCc":1254,"type":"enduro","year":2024,"price":21000}'

curl -X POST http://localhost:8082/api/cars -H "Content-Type: application/json" \
  -d '{"brand":"Tesla","model":"Model 3","fuelType":"electric","doors":4,"seats":5,"year":2024,"price":42000}'
```

> 📖 **Approfondimenti:** [Networking in Compose](https://docs.docker.com/compose/networking/), [Volumes](https://docs.docker.com/storage/volumes/)

---
---

# 🟢 MODULO 6 — `garage-service` con comunicazione tra servizi

> ⏱️ **Durata stimata:** 3 ore
> 🎯 **Obiettivi:** Implementare il terzo microservizio `garage`, che mantiene la lista di veicoli (bikes + cars) di un proprietario e **valida** ogni veicolo chiamando i servizi `bikes` e `cars` via HTTP.

## Introduzione

Il `garage-service` è il primo "vero" microservizio composito: non ha solo un suo dominio, ma **dipende dagli altri due**. È il momento di parlare di:

- **Service-to-service communication** (chiamate REST sincrone con `RestClient` di Spring 6)
- **Failure handling** (cosa succede se `bikes` è down?)
- **Modello dati**: il garage **non duplica** i dati delle bike/car, salva solo l'`id` e il `type`.

> 📖 [RestClient (Spring Framework 6.1+)](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-restclient)

---

## Task 6.1 — Genera il progetto `garage-service`

Da `start.spring.io`:
- Artifact: `garage-service`
- Dependencies: Spring Web, Spring Data JPA, MySQL Driver, Validation, Lombok (opzionale)

Scompatta in `workshop-microservices-java/garage-service/`.

## Task 6.2 — Database `garagedb`

> 💡 Nel `docker-compose.yml` aggiungerai un nuovo blocco `garage-db` (lo facciamo nel Task 6.8). Per ora, sviluppa in locale con un container ad-hoc:

```bash
docker run -d \
  --name mysql-garage \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=garagedb \
  -e MYSQL_USER=garageuser \
  -e MYSQL_PASSWORD=garagepwd \
  -p 3309:3306 \
  mysql:8.0
```

## Task 6.3 — `application.yml`

```yaml
server:
  port: 8083

spring:
  application:
    name: garage-service
  datasource:
    url: jdbc:mysql://localhost:3309/garagedb
    username: garageuser
    password: garagepwd
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true

# URL dei servizi remoti (usiamo i nomi dei service di compose in produzione)
services:
  bikes:
    url: http://localhost:8081
  cars:
    url: http://localhost:8082
```

## Task 6.4 — Entity `Garage` e `Vehicle`

> 💡 **Aiuto** — Modello `Garage` con `@OneToMany`:
> ```java
> // Garage.java
> @Entity
> @Table(name = "garages")
> public class Garage {
>     @Id
>     @GeneratedValue(strategy = GenerationType.IDENTITY)
>     private Long id;
>
>     @NotBlank
>     private String ownerName;
>
>     @NotBlank
>     private String address;
>
>     @OneToMany(mappedBy = "garage", cascade = CascadeType.ALL, orphanRemoval = true)
>     private List<Vehicle> vehicles = new ArrayList<>();
>
>     // getter / setter
> }
> ```
>
> ```java
> // Vehicle.java — riferimento "logico" a una bike o a una car
> @Entity
> @Table(name = "vehicles")
> public class Vehicle {
>     @Id
>     @GeneratedValue(strategy = GenerationType.IDENTITY)
>     private Long id;
>
>     @NotNull
>     private Long externalId;     // id della bike o della car
>
>     @NotBlank
>     private String vehicleType;  // "BIKE" o "CAR"
>
>     @ManyToOne
>     @JoinColumn(name = "garage_id")
>     @JsonIgnore                  // evita ciclo di serializzazione
>     private Garage garage;
>
>     // getter / setter
> }
> ```

## Task 6.5 — Client REST verso `bikes` e `cars`

> 💡 **Aiuto** — `RestClient` Spring 6:
> ```java
> @Configuration
> public class RestClientConfig {
>
>     @Value("${services.bikes.url}")
>     private String bikesUrl;
>
>     @Value("${services.cars.url}")
>     private String carsUrl;
>
>     @Bean(name = "bikesClient")
>     public RestClient bikesClient() {
>         return RestClient.builder().baseUrl(bikesUrl).build();
>     }
>
>     @Bean(name = "carsClient")
>     public RestClient carsClient() {
>         return RestClient.builder().baseUrl(carsUrl).build();
>     }
> }
> ```
>
> ```java
> @Service
> public class VehicleValidator {
>
>     private final RestClient bikesClient;
>     private final RestClient carsClient;
>
>     public VehicleValidator(@Qualifier("bikesClient") RestClient bikesClient,
>                             @Qualifier("carsClient") RestClient carsClient) {
>         this.bikesClient = bikesClient;
>         this.carsClient = carsClient;
>     }
>
>     public boolean exists(String type, Long externalId) {
>         RestClient client = switch (type.toUpperCase()) {
>             case "BIKE" -> bikesClient;
>             case "CAR"  -> carsClient;
>             default -> throw new IllegalArgumentException("Unknown type: " + type);
>         };
>         String path = type.equalsIgnoreCase("BIKE") ? "/api/bikes/" : "/api/cars/";
>         try {
>             client.get().uri(path + externalId).retrieve().toBodilessEntity();
>             return true;
>         } catch (HttpClientErrorException.NotFound e) {
>             return false;
>         }
>     }
> }
> ```

## Task 6.6 — Service e Controller

Implementa `GarageService` (CRUD standard) e `VehicleService` con un metodo `addVehicle(Long garageId, Vehicle v)` che **prima valida** chiamando `VehicleValidator.exists(...)` e solo se OK persiste.

> 💡 **Aiuto** — Endpoint suggeriti per `GarageController`:
> ```
> GET    /api/garages
> GET    /api/garages/{id}
> POST   /api/garages
> PUT    /api/garages/{id}
> DELETE /api/garages/{id}
>
> POST   /api/garages/{id}/vehicles    body: {"externalId":1,"vehicleType":"BIKE"}
> DELETE /api/garages/{id}/vehicles/{vehicleId}
> ```

> 💡 Logica di `addVehicle`:
> ```java
> public Vehicle addVehicle(Long garageId, Vehicle v) {
>     if (!validator.exists(v.getVehicleType(), v.getExternalId())) {
>         throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
>             "Vehicle " + v.getVehicleType() + ":" + v.getExternalId() + " does not exist");
>     }
>     Garage garage = garageRepository.findById(garageId)
>         .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
>     v.setGarage(garage);
>     return vehicleRepository.save(v);
> }
> ```

## Task 6.7 — Avvio in locale e test `curl`

```bash
cd garage-service
./mvnw spring-boot:run
```

```bash
# crea un garage
curl -s -X POST http://localhost:8083/api/garages \
  -H "Content-Type: application/json" \
  -d '{"ownerName":"Mario Rossi","address":"Via Roma 1, Roma"}'
# {"id":1,...}

# ricordati l'id di una bike esistente (es. 1) e aggiungila al garage 1
curl -s -X POST http://localhost:8083/api/garages/1/vehicles \
  -H "Content-Type: application/json" \
  -d '{"externalId":1,"vehicleType":"BIKE"}'

# prova con un id inesistente (es. 999) → deve rispondere 400
curl -i -s -X POST http://localhost:8083/api/garages/1/vehicles \
  -H "Content-Type: application/json" \
  -d '{"externalId":999,"vehicleType":"CAR"}'
```

## Task 6.8 — Aggiungi `garage-service` al `docker-compose.yml`

> 💡 **Aiuto** — Estendi `docker-compose.yml` con due nuovi blocchi:
> ```yaml
>   garage-db:
>     image: mysql:8.0
>     container_name: garage-db
>     environment:
>       MYSQL_ROOT_PASSWORD: root
>       MYSQL_DATABASE: garagedb
>       MYSQL_USER: garageuser
>       MYSQL_PASSWORD: garagepwd
>     volumes:
>       - garage-data:/var/lib/mysql
>     networks:
>       - workshop-net
>     healthcheck:
>       test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-proot"]
>       interval: 5s
>       retries: 10
>
>   garage-service:
>     build: ./garage-service
>     container_name: garage-service
>     ports:
>       - "8083:8083"
>     environment:
>       SPRING_DATASOURCE_URL: jdbc:mysql://garage-db:3306/garagedb
>       SPRING_DATASOURCE_USERNAME: garageuser
>       SPRING_DATASOURCE_PASSWORD: garagepwd
>       SERVICES_BIKES_URL: http://bikes-service:8081
>       SERVICES_CARS_URL: http://cars-service:8082
>     depends_on:
>       garage-db:
>         condition: service_healthy
>       bikes-service:
>         condition: service_started
>       cars-service:
>         condition: service_started
>     networks:
>       - workshop-net
> ```
>
> E aggiungi il volume:
> ```yaml
> volumes:
>   bikes-data:
>   cars-data:
>   garage-data:    # ← nuovo
> ```

> ⚠️ Nota: in compose, i servizi si chiamano per **nome del container** (`bikes-service`, `cars-service`), non per `localhost`. Per questo abbiamo le env `SERVICES_BIKES_URL` e `SERVICES_CARS_URL`.

## Task 6.9 — Build & push immagine `garage-service`

Crea il `Dockerfile` per `garage-service` (uguale a quello dei moduli precedenti, con `EXPOSE 8083`).

```bash
cd garage-service
docker build -t <tuo-username>/garage-service:0.1.0 .
docker push <tuo-username>/garage-service:0.1.0
```

## ✅ Checkpoint Modulo 6

```bash
docker compose down
docker compose up -d --build

docker compose ps   # 6 servizi UP

# end-to-end via stack containerizzato
curl -X POST http://localhost:8081/api/bikes -H "Content-Type: application/json" \
  -d '{"brand":"Yamaha","model":"MT-07","engineCc":689,"type":"naked","year":2024,"price":7800}'

curl -X POST http://localhost:8083/api/garages -H "Content-Type: application/json" \
  -d '{"ownerName":"Lucia Bianchi","address":"Via Milano 10, Milano"}'

curl -X POST http://localhost:8083/api/garages/1/vehicles -H "Content-Type: application/json" \
  -d '{"externalId":1,"vehicleType":"BIKE"}'

curl -s http://localhost:8083/api/garages/1
# deve includere il vehicle appena aggiunto
```

> 📖 **Approfondimenti:** [Resilience4j (Circuit Breaker)](https://resilience4j.readme.io/docs/getting-started), [Service discovery patterns](https://microservices.io/patterns/service-registry.html)

---
---

# 🟢 MODULO 7 — Frontend minimale

> ⏱️ **Durata stimata:** 2h 30m
> 🎯 **Obiettivi:** Realizzare un FE statico in HTML/JS che permette di gestire bikes, cars e garage attraverso le API REST.

## Introduzione

Per non aggiungere complessità (React, Angular, build pipeline), useremo **HTML + Bootstrap + JS vanilla** servito da un container **nginx:alpine**. Le chiamate API sono `fetch()`.

> 📖 [MDN — fetch()](https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API), [Bootstrap 5](https://getbootstrap.com/docs/5.3/getting-started/introduction/)

---

## Task 7.1 — Struttura del FE

Crea `workshop-microservices-java/frontend/`:
```
frontend/
├── Dockerfile
├── nginx.conf
├── index.html
├── bikes.html
├── cars.html
├── garages.html
└── js/
    └── app.js
```

## Task 7.2 — `nginx.conf`

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

## Task 7.3 — `Dockerfile` del FE

```dockerfile
FROM nginx:1.25-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY . /usr/share/nginx/html
EXPOSE 80
```

## Task 7.4 — `index.html` (homepage)

> 💡 **Aiuto** — Layout pronto:
> ```html
> <!DOCTYPE html>
> <html lang="it">
> <head>
>   <meta charset="UTF-8" />
>   <title>Workshop · Garage Manager</title>
>   <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
> </head>
> <body class="bg-light">
>   <nav class="navbar navbar-expand-lg navbar-dark bg-dark">
>     <div class="container">
>       <a class="navbar-brand" href="index.html">🏍️🚗 Garage Manager</a>
>       <ul class="navbar-nav">
>         <li class="nav-item"><a class="nav-link" href="bikes.html">Bikes</a></li>
>         <li class="nav-item"><a class="nav-link" href="cars.html">Cars</a></li>
>         <li class="nav-item"><a class="nav-link" href="garages.html">Garages</a></li>
>       </ul>
>     </div>
>   </nav>
>   <main class="container py-5 text-center">
>     <h1>Benvenuto nel Garage Manager</h1>
>     <p class="lead">Workshop Microservizi Java — Eleventh</p>
>   </main>
> </body>
> </html>
> ```

## Task 7.5 — `bikes.html` (lista + form)

> 💡 **Aiuto** — Struttura della pagina (ripetibile per cars):
> ```html
> <!DOCTYPE html>
> <html lang="it">
> <head>
>   <meta charset="UTF-8" />
>   <title>Bikes</title>
>   <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
> </head>
> <body class="bg-light">
>   <nav class="navbar navbar-dark bg-dark">
>     <div class="container">
>       <a class="navbar-brand" href="index.html">🏍️🚗 Garage Manager</a>
>     </div>
>   </nav>
>   <main class="container py-4">
>     <h2>Bikes</h2>
>
>     <form id="bike-form" class="row g-2 mb-4">
>       <div class="col"><input class="form-control" name="brand" placeholder="Brand" required></div>
>       <div class="col"><input class="form-control" name="model" placeholder="Model" required></div>
>       <div class="col"><input type="number" class="form-control" name="engineCc" placeholder="cc" required></div>
>       <div class="col"><input class="form-control" name="type" placeholder="Type" required></div>
>       <div class="col"><input type="number" class="form-control" name="year" placeholder="Year" required></div>
>       <div class="col"><input type="number" step="0.01" class="form-control" name="price" placeholder="Price" required></div>
>       <div class="col-auto"><button class="btn btn-primary">Add</button></div>
>     </form>
>
>     <table class="table table-striped">
>       <thead><tr>
>         <th>ID</th><th>Brand</th><th>Model</th><th>cc</th><th>Type</th><th>Year</th><th>Price</th><th></th>
>       </tr></thead>
>       <tbody id="bikes-table"></tbody>
>     </table>
>   </main>
>
>   <script src="js/app.js"></script>
>   <script>renderList('bikes', 'bikes-table', 'bike-form');</script>
> </body>
> </html>
> ```

Replica la stessa struttura per `cars.html` (campi: brand, model, fuelType, doors, seats, year, price).

## Task 7.6 — `garages.html`

Pagina più articolata:
- form per creare un garage (ownerName, address)
- per ogni garage in lista, mostra i veicoli e un mini-form per aggiungerne uno (input `externalId`, select `vehicleType`)

## Task 7.7 — `js/app.js`

> 💡 **Aiuto** — Helper riusabili:
> ```javascript
> // js/app.js
> const API = {
>   bikes:   "http://localhost:8081/api/bikes",
>   cars:    "http://localhost:8082/api/cars",
>   garages: "http://localhost:8083/api/garages",
> };
>
> async function fetchAll(resource) {
>   const r = await fetch(API[resource]);
>   return r.json();
> }
>
> async function create(resource, payload) {
>   const r = await fetch(API[resource], {
>     method: "POST",
>     headers: { "Content-Type": "application/json" },
>     body: JSON.stringify(payload),
>   });
>   if (!r.ok) throw new Error("Errore " + r.status);
>   return r.json();
> }
>
> async function remove(resource, id) {
>   const r = await fetch(`${API[resource]}/${id}`, { method: "DELETE" });
>   if (!r.ok) throw new Error("Errore " + r.status);
> }
>
> function rowFor(resource, item) {
>   const tr = document.createElement("tr");
>   const fields = Object.keys(item).filter(k => k !== "id" && k !== "vehicles");
>   tr.innerHTML = `<td>${item.id}</td>` +
>     fields.map(f => `<td>${item[f] ?? ""}</td>`).join("") +
>     `<td><button class="btn btn-sm btn-danger" data-id="${item.id}">🗑</button></td>`;
>   tr.querySelector("button").onclick = async () => {
>     await remove(resource, item.id);
>     tr.remove();
>   };
>   return tr;
> }
>
> async function renderList(resource, tableId, formId) {
>   const tbody = document.getElementById(tableId);
>   const form  = document.getElementById(formId);
>
>   async function refresh() {
>     tbody.innerHTML = "";
>     const items = await fetchAll(resource);
>     items.forEach(i => tbody.appendChild(rowFor(resource, i)));
>   }
>
>   form.onsubmit = async (e) => {
>     e.preventDefault();
>     const data = Object.fromEntries(new FormData(form).entries());
>     // converti i numerici
>     ["engineCc","year","price","doors","seats"].forEach(k => {
>       if (data[k] !== undefined) data[k] = Number(data[k]);
>     });
>     await create(resource, data);
>     form.reset();
>     refresh();
>   };
>
>   refresh();
> }
> ```

## Task 7.8 — Aggiungi il FE al `docker-compose.yml`

> 💡 **Aiuto:**
> ```yaml
>   frontend:
>     build: ./frontend
>     container_name: frontend
>     ports:
>       - "8080:80"
>     networks:
>       - workshop-net
>     depends_on:
>       - bikes-service
>       - cars-service
>       - garage-service
> ```

> ⚠️ **CORS:** assicurati che i tre controller Spring abbiano `@CrossOrigin(origins = "*")` (lo abbiamo già messo nel modulo 2). In produzione si limita a domini specifici.

## ✅ Checkpoint Modulo 7

```bash
docker compose down
docker compose up -d --build
```

Apri il browser su [http://localhost:8080](http://localhost:8080) e verifica che:
- riesci a creare/cancellare bikes
- riesci a creare/cancellare cars
- riesci a creare un garage e aggiungere veicoli (con validazione: provando un id inesistente devi vedere un errore)

> 📖 **Approfondimenti:** [CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS), [nginx serving SPA](https://www.nginx.com/blog/serving-static-content-with-nginx/)

---
---

# 🟡 MODULO 8 — (Opzionale) Unit Test e Integration Test

> ⏱️ **Durata stimata:** 2 ore
> 🎯 **Obiettivi:** Aggiungere una suite di test unitari (con mock) e integration test (con MySQL "vero" via Testcontainers).

## Introduzione

- **Unit test**: testano una singola classe in isolamento (con dipendenze mockate)
- **Integration test**: testano il microservizio nel suo insieme, con DB vero, server vero

> 📖 [Spring Boot Testing](https://docs.spring.io/spring-boot/reference/testing/), [Testcontainers](https://java.testcontainers.org/)

## Task 8.1 — Unit test del service (`bikes-service`)

> 💡 **Aiuto:**
> ```java
> @ExtendWith(MockitoExtension.class)
> class BikeServiceTest {
>
>     @Mock BikeRepository repository;
>     @InjectMocks BikeService service;
>
>     @Test
>     void shouldFindBikeById() {
>         Bike bike = new Bike();
>         bike.setId(1L);
>         when(repository.findById(1L)).thenReturn(Optional.of(bike));
>
>         Optional<Bike> result = service.findById(1L);
>
>         assertThat(result).isPresent();
>         assertThat(result.get().getId()).isEqualTo(1L);
>     }
> }
> ```

## Task 8.2 — Integration test con `@SpringBootTest` + Testcontainers

Aggiungi al `pom.xml`:
```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>mysql</artifactId>
  <version>1.19.3</version>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <version>1.19.3</version>
  <scope>test</scope>
</dependency>
```

> 💡 **Aiuto:**
> ```java
> @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
> @Testcontainers
> class BikeIntegrationTest {
>
>     @Container
>     static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
>         .withDatabaseName("bikesdb")
>         .withUsername("user")
>         .withPassword("pwd");
>
>     @DynamicPropertySource
>     static void props(DynamicPropertyRegistry registry) {
>         registry.add("spring.datasource.url", mysql::getJdbcUrl);
>         registry.add("spring.datasource.username", mysql::getUsername);
>         registry.add("spring.datasource.password", mysql::getPassword);
>     }
>
>     @Autowired TestRestTemplate rest;
>
>     @Test
>     void shouldCreateAndFetchBike() {
>         Bike bike = new Bike();
>         bike.setBrand("Aprilia");
>         bike.setModel("RS 660");
>         bike.setEngineCc(659);
>         bike.setType("sport");
>         bike.setYear(2024);
>         bike.setPrice(11500.0);
>
>         Bike created = rest.postForObject("/api/bikes", bike, Bike.class);
>         assertThat(created.getId()).isNotNull();
>
>         Bike fetched = rest.getForObject("/api/bikes/" + created.getId(), Bike.class);
>         assertThat(fetched.getModel()).isEqualTo("RS 660");
>     }
> }
> ```

## Task 8.3 — Esegui i test

```bash
./mvnw test
```

## ✅ Checkpoint Modulo 8

```bash
./mvnw test
# tutti i test devono passare (BUILD SUCCESS)
```

> 📖 **Approfondimenti:** [AssertJ](https://assertj.github.io/doc/), [Mockito](https://site.mockito.org/), [Testcontainers patterns](https://java.testcontainers.org/test_framework_integration/junit_5/)

---
---

# 🟡 MODULO 9 — (Opzionale) Deploy su AWS EKS

> ⏱️ **Durata stimata:** 1h 30m
> 🎯 **Obiettivi:** Deployare i microservizi su un cluster Kubernetes managed (AWS EKS) usando manifest YAML.

## Prerequisiti

- Account AWS con permessi per EKS, ECR, IAM
- AWS CLI v2 installato e configurato (`aws configure`)
- `eksctl` ([guida](https://eksctl.io/installation/))
- `kubectl` ([guida](https://kubernetes.io/docs/tasks/tools/))

> 📖 [Amazon EKS](https://docs.aws.amazon.com/eks/), [Kubernetes Concepts](https://kubernetes.io/docs/concepts/)

## Task 9.1 — Crea il cluster EKS

```bash
eksctl create cluster \
  --name workshop-eks \
  --region eu-south-1 \
  --nodes 2 \
  --node-type t3.medium \
  --managed
# attesa ~15 minuti
```

## Task 9.2 — Push immagini su ECR

```bash
aws ecr create-repository --repository-name bikes-service
aws ecr create-repository --repository-name cars-service
aws ecr create-repository --repository-name garage-service
aws ecr create-repository --repository-name frontend

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGION=eu-south-1
ECR=${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com

aws ecr get-login-password --region ${REGION} | docker login --username AWS --password-stdin ${ECR}

# tag & push (ripeti per ogni servizio)
docker tag <tuo-username>/bikes-service:0.1.0 ${ECR}/bikes-service:0.1.0
docker push ${ECR}/bikes-service:0.1.0
```

## Task 9.3 — Manifest Kubernetes

Crea `k8s/bikes-deployment.yaml`:

> 💡 **Aiuto:**
> ```yaml
> apiVersion: apps/v1
> kind: Deployment
> metadata:
>   name: bikes-service
> spec:
>   replicas: 2
>   selector:
>     matchLabels:
>       app: bikes-service
>   template:
>     metadata:
>       labels:
>         app: bikes-service
>     spec:
>       containers:
>         - name: bikes-service
>           image: <ACCOUNT>.dkr.ecr.eu-south-1.amazonaws.com/bikes-service:0.1.0
>           ports:
>             - containerPort: 8081
>           env:
>             - name: SPRING_DATASOURCE_URL
>               value: jdbc:mysql://bikes-db:3306/bikesdb
>             - name: SPRING_DATASOURCE_USERNAME
>               valueFrom: { secretKeyRef: { name: bikes-secret, key: user } }
>             - name: SPRING_DATASOURCE_PASSWORD
>               valueFrom: { secretKeyRef: { name: bikes-secret, key: password } }
> ---
> apiVersion: v1
> kind: Service
> metadata:
>   name: bikes-service
> spec:
>   selector:
>     app: bikes-service
>   ports:
>     - port: 8081
>       targetPort: 8081
>   type: ClusterIP
> ```

> ℹ️ Per i database in produzione si usa **Amazon RDS for MySQL**, non un MySQL containerizzato dentro EKS. Per il workshop, puoi mantenere MySQL come `StatefulSet` o passare a RDS.

## Task 9.4 — Apply

```bash
kubectl apply -f k8s/
kubectl get pods
kubectl get svc
```

## Task 9.5 — Esponi il FE con un Load Balancer

Cambia il `Service` del FE in `type: LoadBalancer` per ottenere un endpoint pubblico AWS ELB.

```bash
kubectl get svc frontend -w
# attendi che la colonna EXTERNAL-IP sia popolata
```

## ✅ Checkpoint Modulo 9

```bash
kubectl get pods         # tutti Running
curl http://<ELB-DNS>/   # FE raggiungibile
```

> ⚠️ **Cleanup obbligatorio per non incorrere in costi:**
> ```bash
> eksctl delete cluster --name workshop-eks --region eu-south-1
> ```

> 📖 **Approfondimenti:** [EKS Best Practices](https://aws.github.io/aws-eks-best-practices/), [Kubernetes Networking](https://kubernetes.io/docs/concepts/services-networking/)

---
---

# 🟡 MODULO 10 — (Opzionale) Pipeline CI/CD su AWS

> ⏱️ **Durata stimata:** 30m (panoramica)
> 🎯 **Obiettivi:** Capire come automatizzare build → push ECR → deploy EKS con AWS CodePipeline + CodeBuild.

## Architettura della pipeline

```
GitHub  →  CodePipeline  →  CodeBuild  →  ECR  →  EKS (kubectl apply)
   ↑           │
   │           │ trigger su push su main
   └───────────┘
```

## Task 10.1 — `buildspec.yml` per CodeBuild

Alla radice di ogni microservizio:

> 💡 **Aiuto:**
> ```yaml
> version: 0.2
> phases:
>   pre_build:
>     commands:
>       - aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $ECR_REGISTRY
>       - REPOSITORY_URI=$ECR_REGISTRY/bikes-service
>       - IMAGE_TAG=$(echo $CODEBUILD_RESOLVED_SOURCE_VERSION | cut -c 1-8)
>   build:
>     commands:
>       - docker build -t $REPOSITORY_URI:$IMAGE_TAG ./bikes-service
>       - docker tag $REPOSITORY_URI:$IMAGE_TAG $REPOSITORY_URI:latest
>   post_build:
>     commands:
>       - docker push $REPOSITORY_URI:$IMAGE_TAG
>       - docker push $REPOSITORY_URI:latest
>       - aws eks update-kubeconfig --region $AWS_REGION --name workshop-eks
>       - kubectl set image deployment/bikes-service bikes-service=$REPOSITORY_URI:$IMAGE_TAG
> ```

## Task 10.2 — CodePipeline

Crea su AWS Console una pipeline a 3 stage:
1. **Source**: GitHub (connection via CodeStar)
2. **Build**: CodeBuild project che usa il `buildspec.yml`
3. **Deploy**: deploy implicito via `kubectl set image` nel `post_build`

> 📖 [AWS CodePipeline](https://docs.aws.amazon.com/codepipeline/), [CodeBuild buildspec](https://docs.aws.amazon.com/codebuild/latest/userguide/build-spec-ref.html)

## ✅ Checkpoint Modulo 10

Push una modifica sul branch `main` → la pipeline deve triggerare un nuovo deploy.

---
---

# 📊 Riepilogo competenze acquisite

| Modulo | Competenza |
|:------:|------------|
| 0 | Setup ambiente Java + Docker |
| 1 | Versionamento Git e gestione registry |
| 2 | Spring Boot · JPA · REST |
| 3 | Replicazione di pattern microservizi |
| 4 | Docker · multi-stage · Alpine |
| 5 | Docker Compose · networking · volumi |
| 6 | Service-to-service via REST · validazione cross-service |
| 7 | Frontend statico · CORS · `fetch()` |
| 8 | Test unitari + Testcontainers |
| 9 | Kubernetes su EKS |
| 10 | CI/CD su AWS CodePipeline |

---

## 🚀 Comandi di riferimento rapido

```bash
# build di un singolo servizio
./mvnw clean package -DskipTests

# avvio in locale (dev)
./mvnw spring-boot:run

# build immagine
docker build -t <user>/<svc>:<tag> .

# avvio stack completo
docker compose up -d --build
docker compose logs -f
docker compose down -v   # ⚠️ con -v cancella i volumi

# test
./mvnw test
```

---

## 📖 Risorse principali

| Argomento | Link |
|-----------|------|
| Spring Boot reference | [docs.spring.io/spring-boot](https://docs.spring.io/spring-boot/index.html) |
| Spring Initializr | [start.spring.io](https://start.spring.io/) |
| Spring Data JPA | [docs.spring.io/spring-data/jpa](https://docs.spring.io/spring-data/jpa/reference/) |
| Docker docs | [docs.docker.com](https://docs.docker.com/) |
| Compose spec | [compose-spec.io](https://compose-spec.io/) |
| MySQL 8 | [dev.mysql.com/doc](https://dev.mysql.com/doc/) |
| MDN — Fetch API | [developer.mozilla.org](https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API) |
| Testcontainers | [java.testcontainers.org](https://java.testcontainers.org/) |
| AWS EKS | [docs.aws.amazon.com/eks](https://docs.aws.amazon.com/eks/) |
| AWS CodePipeline | [docs.aws.amazon.com/codepipeline](https://docs.aws.amazon.com/codepipeline/) |
| 12-Factor App | [12factor.net](https://12factor.net/) |

---

## 🎓 Prossimi passi suggeriti

Una volta completati i moduli obbligatori, prova a:
- introdurre **Spring Cloud Gateway** come API gateway davanti ai 3 servizi
- aggiungere **OpenAPI/Swagger** (`springdoc-openapi`) per documentare le API
- sostituire le chiamate sincrone `garage → bikes/cars` con eventi asincroni su **RabbitMQ** o **Kafka**
- implementare **circuit breaker** con **Resilience4j** sulle chiamate cross-service
- aggiungere **observability**: Spring Boot Actuator + Prometheus + Grafana

Buon lavoro! 🚀
